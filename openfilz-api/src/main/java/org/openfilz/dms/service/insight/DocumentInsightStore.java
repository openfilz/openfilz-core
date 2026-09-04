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

    private final DatabaseClient databaseClient;
    private final AiDocumentInsightRepository repository;

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
