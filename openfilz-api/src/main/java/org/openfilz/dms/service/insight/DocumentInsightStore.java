package org.openfilz.dms.service.insight;

import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.response.DocumentInsightView;
import org.openfilz.dms.entity.AiDocumentInsight;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.AiDocumentInsightRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence of {@code ai_document_insights}: tier-1 rows are upserted from the Tika pass that
 * indexing / embedding already run (so a replaced version overwrites the file metadata without
 * touching tier-2 columns); reads serve the REST endpoint, the AI tools and the reorganisation
 * inventory.
 */
@Slf4j
@Service
@Lazy
@RequiredArgsConstructor
public class DocumentInsightStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String UPSERT_TIER1 = """
            INSERT INTO ai_document_insights (document_id, file_title, file_author, file_created_at, file_modified_at,
                                              page_count, language, tier, status, created_at, updated_at)
            VALUES (:id, :title, :author, :createdAt, :modifiedAt, :pages, :language, 1, 'DONE', now(), now())
            ON CONFLICT (document_id) DO UPDATE SET
                file_title = EXCLUDED.file_title,
                file_author = EXCLUDED.file_author,
                file_created_at = EXCLUDED.file_created_at,
                file_modified_at = EXCLUDED.file_modified_at,
                page_count = EXCLUDED.page_count,
                language = COALESCE(EXCLUDED.language, ai_document_insights.language),
                updated_at = now()""";

    private static final String MARK_PENDING = """
            INSERT INTO ai_document_insights (document_id, tier, status, created_at, updated_at)
            VALUES (:id, 1, 'PENDING', now(), now())
            ON CONFLICT (document_id) DO UPDATE SET status = 'PENDING', error = NULL, updated_at = now()""";

    private static final String MARK_OUTCOME = """
            INSERT INTO ai_document_insights (document_id, tier, status, error, created_at, updated_at)
            VALUES (:id, 1, :status, :error, now(), now())
            ON CONFLICT (document_id) DO UPDATE SET status = :status, error = :error, updated_at = now()""";

    private static final String UPSERT_TIER2 = """
            INSERT INTO ai_document_insights (document_id, category, summary, keywords, entities, language, tier, model,
                                              prompt_version, status, error, created_at, updated_at)
            VALUES (:id, :category, :summary, :keywords, :entities, :language, 2, :model, :promptVersion, 'DONE', NULL, now(), now())
            ON CONFLICT (document_id) DO UPDATE SET
                category = EXCLUDED.category,
                summary = EXCLUDED.summary,
                keywords = EXCLUDED.keywords,
                entities = EXCLUDED.entities,
                language = COALESCE(ai_document_insights.language, EXCLUDED.language),
                tier = 2,
                model = EXCLUDED.model,
                prompt_version = EXCLUDED.prompt_version,
                status = 'DONE',
                error = NULL,
                updated_at = now()""";

    /** Active files without a current DONE tier-2 row (none, older prompt version, not DONE), or every file when forced; most recently updated first. */
    private static final String BACKFILL_CANDIDATES = """
            SELECT d.id FROM documents d
              LEFT JOIN ai_document_insights i ON i.document_id = d.id
             WHERE d.type = 'FILE' AND d.active = true
               %s
               AND (:force OR i.document_id IS NULL OR COALESCE(i.tier, 1) < 2 OR i.status <> 'DONE'
                    OR i.prompt_version IS NULL OR i.prompt_version < :version)
             ORDER BY d.updated_at DESC NULLS LAST
             LIMIT :limit""";

    private static final String SUBTREE_FILTER = """
            AND d.parent_id IN (WITH RECURSIVE subtree AS (
                    SELECT id FROM documents WHERE id = :folderId
                    UNION ALL
                    SELECT c.id FROM documents c JOIN subtree s ON c.parent_id = s.id)
                SELECT id FROM subtree)""";

    private final DatabaseClient databaseClient;
    private final AiDocumentInsightRepository repository;

    // ── tier 2 ──────────────────────────────────────────────────────────────

    public Mono<Void> markPending(UUID documentId) {
        return databaseClient.sql(MARK_PENDING).bind("id", documentId).fetch().rowsUpdated().then();
    }

    public Mono<Void> markFailed(UUID documentId, String error) {
        return markOutcome(documentId, AiDocumentInsight.STATUS_FAILED, error);
    }

    public Mono<Void> markSkipped(UUID documentId, String reason) {
        return markOutcome(documentId, AiDocumentInsight.STATUS_SKIPPED, reason);
    }

    private Mono<Void> markOutcome(UUID documentId, String status, String error) {
        String message = error == null ? null : error.length() > 512 ? error.substring(0, 512) : error;
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(MARK_OUTCOME)
                .bind("id", documentId)
                .bind("status", status);
        spec = bindNullable(spec, "error", message, String.class);
        return spec.fetch().rowsUpdated().then();
    }

    /** Tier 2: the model's answer. Tier-1 columns of the row are left as they are (a Tika language wins over the model's). */
    public Mono<Void> saveEnrichment(UUID documentId, InsightResult result, String model, int promptVersion) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(UPSERT_TIER2)
                .bind("id", documentId)
                .bind("category", result.category() == null ? InsightResult.OTHER : result.category())
                .bind("keywords", result.keywords() == null ? new String[0] : result.keywords().toArray(new String[0]))
                .bind("promptVersion", promptVersion);
        spec = bindNullable(spec, "summary", result.summary(), String.class);
        spec = bindNullable(spec, "language", result.language(), String.class);
        spec = bindNullable(spec, "model", model, String.class);
        spec = result.entities() == null || result.entities().isEmpty()
                ? spec.bindNull("entities", Json.class)
                : spec.bind("entities", Json.of(JSON.writeValueAsString(result.entities())));
        return spec.fetch().rowsUpdated().then();
    }

    public Flux<UUID> findBackfillCandidates(UUID folderId, boolean force, int promptVersion, int limit) {
        String sql = BACKFILL_CANDIDATES.formatted(folderId == null ? "" : SUBTREE_FILTER);
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql)
                .bind("force", force)
                .bind("version", promptVersion)
                .bind("limit", limit);
        if (folderId != null) {
            spec = spec.bind("folderId", folderId);
        }
        return spec.map(row -> row.get("id", UUID.class)).all();
    }

    // ── tier 1 ──────────────────────────────────────────────────────────────

    /** Tier 1: the file's own metadata. Tier-2 columns of an existing row are left as they are. */
    public Mono<Void> saveFileMetadata(Document document, TikaFileMetadata metadata) {
        if (document == null || document.getId() == null || document.getType() != DocumentType.FILE) {
            return Mono.empty();
        }
        TikaFileMetadata values = metadata == null ? TikaFileMetadata.EMPTY : metadata;
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(UPSERT_TIER1).bind("id", document.getId());
        spec = bindNullable(spec, "title", values.title(), String.class);
        spec = bindNullable(spec, "author", values.author(), String.class);
        spec = bindNullable(spec, "createdAt", values.createdAt(), OffsetDateTime.class);
        spec = bindNullable(spec, "modifiedAt", values.modifiedAt(), OffsetDateTime.class);
        spec = bindNullable(spec, "pages", values.pageCount(), Integer.class);
        spec = bindNullable(spec, "language", values.language(), String.class);
        return spec.fetch().rowsUpdated()
                .doOnSuccess(n -> log.debug("[INSIGHTS] tier-1 saved for '{}' ({}): {}", document.getName(), document.getId(), values))
                .then();
    }

    public Mono<AiDocumentInsight> find(UUID documentId) {
        return documentId == null ? Mono.empty() : repository.findById(documentId);
    }

    public Flux<AiDocumentInsight> findAll(Collection<UUID> documentIds) {
        return documentIds == null || documentIds.isEmpty() ? Flux.empty() : repository.findAllById(documentIds);
    }

    public Mono<Void> delete(UUID documentId) {
        return documentId == null ? Mono.empty() : repository.deleteById(documentId);
    }

    public static DocumentInsightView toView(AiDocumentInsight entity) {
        return new DocumentInsightView(
                entity.getDocumentId(),
                entity.getFileTitle(),
                entity.getFileAuthor(),
                entity.getFileCreatedAt(),
                entity.getFileModifiedAt(),
                entity.getPageCount(),
                entity.getLanguage(),
                entity.getCategory(),
                entity.getSummary(),
                entity.getKeywords() == null ? List.of() : List.of(entity.getKeywords()),
                entitiesOf(entity.getEntities()),
                entity.getTier() == null ? 1 : entity.getTier(),
                entity.getModel(),
                entity.getPromptVersion(),
                entity.getStatus(),
                entity.getError(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    /** The non-null, model-facing fields of an insight, for tool answers and inventories. */
    public static Map<String, Object> compact(DocumentInsightView view) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (view.category() != null) out.put("category", view.category());
        if (view.summary() != null) out.put("summary", view.summary());
        if (view.keywords() != null && !view.keywords().isEmpty()) out.put("keywords", view.keywords());
        if (view.entities() != null && !view.entities().isEmpty()) out.put("entities", view.entities());
        if (view.language() != null) out.put("language", view.language());
        if (view.fileTitle() != null) out.put("fileTitle", view.fileTitle());
        if (view.fileAuthor() != null) out.put("fileAuthor", view.fileAuthor());
        if (view.fileCreatedAt() != null) out.put("fileCreatedAt", view.fileCreatedAt().toLocalDate().toString());
        if (view.fileModifiedAt() != null) out.put("fileModifiedAt", view.fileModifiedAt().toLocalDate().toString());
        if (view.pageCount() != null) out.put("pageCount", view.pageCount());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entitiesOf(Json json) {
        if (json == null) {
            return Map.of();
        }
        try {
            Map<String, Object> map = JSON.readValue(json.asString(), Map.class);
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static <T> DatabaseClient.GenericExecuteSpec bindNullable(DatabaseClient.GenericExecuteSpec spec, String name,
                                                                       T value, Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }
}
