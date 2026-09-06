package org.openfilz.dms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.dto.response.EmbeddingBackfillStatus;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.DocumentEmbeddingService;
import org.springframework.context.annotation.Lazy;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Embeds existing documents into the vector store — the operator side of a provider switch.
 * <p>
 * Every vector was produced by one embedding model, and vectors from different models are not
 * comparable, so changing the provider means wiping the store and embedding every document
 * again; before this job the only way was to re-upload each file. A backfill selects the active
 * files without a chunk in the store (or every file when forced), optionally under one folder,
 * and runs {@link DocumentEmbeddingService#reembed} on each — the text from the search index
 * when full-text is on, a fresh Tika pass on the stored file otherwise. It also repairs a
 * document whose embedding failed at upload. Insights are not re-run: the model already gave
 * its verdict on that text.
 * <p>
 * Same shape as the insights backfill: an in-memory job followed through its id, bounded
 * concurrency ({@code openfilz.ai.embedding.backfill-concurrency}), a capped candidate list.
 */
@Slf4j
@Service
@Lazy
public class EmbeddingBackfillService {

    static final int CANDIDATE_LIMIT = 10_000;

    /** Active files whose id tags no chunk (or every file when forced), most recently updated first. */
    private static final String CANDIDATES = """
            SELECT d.id FROM documents d
              LEFT JOIN (SELECT DISTINCT metadata->>'document_id' AS document_id FROM vector_store) v
                     ON v.document_id = d.id::text
             WHERE d.type = 'FILE' AND d.active = true
               %s
               AND (:force OR v.document_id IS NULL)
             ORDER BY d.updated_at DESC NULLS LAST
             LIMIT :limit""";

    private static final String SUBTREE_FILTER = """
            AND d.parent_id IN (WITH RECURSIVE subtree AS (
                    SELECT id FROM documents WHERE id = :folderId
                    UNION ALL
                    SELECT c.id FROM documents c JOIN subtree s ON c.parent_id = s.id)
                SELECT id FROM subtree)""";

    private final DatabaseClient databaseClient;
    private final DocumentRepository documentRepository;
    private final DocumentEmbeddingService embeddingService;
    private final AiProperties aiProperties;
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();

    public EmbeddingBackfillService(DatabaseClient databaseClient, DocumentRepository documentRepository,
                                    @Lazy DocumentEmbeddingService embeddingService, AiProperties aiProperties) {
        this.databaseClient = databaseClient;
        this.documentRepository = documentRepository;
        this.embeddingService = embeddingService;
        this.aiProperties = aiProperties;
    }

    /** An in-memory job; a restart loses the handle, and a new backfill simply picks up what is still missing. */
    private static final class Job {
        final UUID id = UUID.randomUUID();
        final UUID folderId;
        final boolean force;
        final OffsetDateTime startedAt = OffsetDateTime.now();
        final AtomicInteger total = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
        volatile boolean enumerated;
        volatile OffsetDateTime finishedAt;

        Job(UUID folderId, boolean force) {
            this.folderId = folderId;
            this.force = force;
        }

        boolean finished() {
            return enumerated && done.get() + failed.get() + skipped.get() >= total.get();
        }

        void settle() {
            if (finished() && finishedAt == null) {
                finishedAt = OffsetDateTime.now();
            }
        }

        EmbeddingBackfillStatus snapshot() {
            boolean finished = finished();
            return new EmbeddingBackfillStatus(id, folderId, force,
                    finished ? EmbeddingBackfillStatus.DONE : EmbeddingBackfillStatus.RUNNING,
                    total.get(), done.get(), failed.get(), skipped.get(), startedAt,
                    finished ? (finishedAt != null ? finishedAt : OffsetDateTime.now()) : null);
        }
    }

    /** Starts a job and returns its handle at once; the work runs on the bounded-elastic scheduler. */
    public Mono<EmbeddingBackfillStatus> backfill(UUID folderId, boolean force) {
        Job job = new Job(folderId, force);
        jobs.put(job.id, job);
        int concurrency = Math.max(1, aiProperties.getEmbedding().getBackfillConcurrency());
        candidates(folderId, force)
                .collectList()
                .doOnNext(ids -> {
                    job.total.set(ids.size());
                    job.enumerated = true;
                    job.settle();
                    log.info("[AI-EMBED] backfill {} queued {} document(s) (folder={}, force={}, concurrency={})",
                            job.id, ids.size(), folderId, force, concurrency);
                })
                .flatMapMany(Flux::fromIterable)
                .flatMap(id -> process(job, id), concurrency)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(v -> { }, e -> {
                    log.error("[AI-EMBED] backfill {} failed to enumerate documents: {}", job.id, e.toString());
                    job.enumerated = true;
                    job.settle();
                }, () -> log.info("[AI-EMBED] backfill {} finished: {} embedded, {} failed, {} skipped of {}",
                        job.id, job.done.get(), job.failed.get(), job.skipped.get(), job.total.get()));
        return Mono.just(job.snapshot());
    }

    public Optional<EmbeddingBackfillStatus> backfillStatus(UUID jobId) {
        return Optional.ofNullable(jobs.get(jobId)).map(Job::snapshot);
    }

    Flux<UUID> candidates(UUID folderId, boolean force) {
        String sql = CANDIDATES.formatted(folderId == null ? "" : SUBTREE_FILTER);
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql)
                .bind("force", force)
                .bind("limit", CANDIDATE_LIMIT);
        if (folderId != null) {
            spec = spec.bind("folderId", folderId);
        }
        return spec.map(row -> row.get("id", UUID.class)).all();
    }

    private Mono<Void> process(Job job, UUID documentId) {
        return documentRepository.findById(documentId)
                .filter(document -> document.getType() == DocumentType.FILE && !Boolean.FALSE.equals(document.getActive()))
                .flatMap(embeddingService::reembed)
                .map(chunks -> chunks > 0 ? "done" : "skipped")
                .defaultIfEmpty("skipped")
                .onErrorResume(e -> {
                    log.warn("[AI-EMBED] backfill {}: embedding of {} failed: {}", job.id, documentId, e.toString());
                    return Mono.just("failed");
                })
                .doOnNext(outcome -> {
                    switch (outcome) {
                        case "done" -> job.done.incrementAndGet();
                        case "failed" -> job.failed.incrementAndGet();
                        default -> job.skipped.incrementAndGet();
                    }
                    job.settle();
                })
                .then();
    }
}
