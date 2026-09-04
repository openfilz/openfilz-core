package org.openfilz.dms.service.insight;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.dto.response.InsightBackfillStatus;
import org.openfilz.dms.entity.AiDocumentInsight;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.OpenSearchDocumentKey;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.IndexService;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.ai.AiFallbackChain;
import org.openfilz.dms.service.ai.UserChatClientResolver;
import org.openfilz.dms.service.ai.UserChatClientResolver.ResolvedChat;
import org.openfilz.dms.service.impl.TikaService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The real tier-2 insight service: one model call per document on a bounded queue.
 * <p>
 * Never on the upload path — {@link #enqueue} returns at once and the queue drains on
 * {@code boundedElastic} with {@code openfilz.ai.insights.concurrency} workers. The text is the
 * head the indexing pass already extracted ({@code max-chars}); documents above
 * {@code max-file-size} or without text are SKIPPED, a model answer that is not the JSON
 * contract is FAILED (never a half row), and the daily cap turns the rest into SKIPPED rows
 * that the backfill picks up later. The model is the deployment's chat model unless
 * {@code openfilz.ai.insights.model} names a cheaper {@code provider:model}; BYOK is not
 * consulted, insights are deployment-level data.
 */
@Slf4j
@Service
@Lazy
@Qualifier("aiDocumentInsightService")
public class AiDocumentInsightService implements DocumentInsightService {

    /** Bump when the prompt or the output contract changes: a backfill with force re-enriches older rows. */
    public static final int PROMPT_VERSION = 1;
    /** Marker the test configuration keys its stub on; also documents which prompt produced a row. */
    static final String PROMPT_MARKER = "INSIGHTS_V1";

    private static final int MAX_QUEUE = 20_000;
    private static final int BACKFILL_LIMIT = 10_000;

    private final AiProperties aiProperties;
    private final DocumentInsightStore store;
    private final UserChatClientResolver resolver;
    private final AiFallbackChain fallbackChain;
    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final TikaService tikaService;
    private final ObjectProvider<IndexService> indexServiceProvider;

    private final Sinks.Many<Task> queue = Sinks.many().unicast().onBackpressureBuffer();
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger enrichedToday = new AtomicInteger();
    private volatile LocalDate today = LocalDate.now();
    private volatile Disposable worker;
    private volatile ResolvedChat model;

    public AiDocumentInsightService(AiProperties aiProperties, DocumentInsightStore store,
                                    UserChatClientResolver resolver, AiFallbackChain fallbackChain,
                                    DocumentRepository documentRepository, StorageService storageService,
                                    TikaService tikaService, ObjectProvider<IndexService> indexServiceProvider) {
        this.aiProperties = aiProperties;
        this.store = store;
        this.resolver = resolver;
        this.fallbackChain = fallbackChain;
        this.documentRepository = documentRepository;
        this.storageService = storageService;
        this.tikaService = tikaService;
        this.indexServiceProvider = indexServiceProvider;
    }

    /** One queued enrichment: the document to enrich, the text head when already known, the job it belongs to. */
    private record Task(UUID documentId, String textHead, UUID jobId) {
    }

    /** An in-memory backfill job; a restart simply re-enqueues what is not DONE. */
    private static final class Job {
        final UUID id = UUID.randomUUID();
        final UUID folderId;
        final boolean force;
        final OffsetDateTime startedAt = OffsetDateTime.now();
        final AtomicInteger total = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
        volatile boolean enqueued;
        volatile OffsetDateTime finishedAt;

        Job(UUID folderId, boolean force) {
            this.folderId = folderId;
            this.force = force;
        }

        void finishOne(String outcome) {
            switch (outcome) {
                case AiDocumentInsight.STATUS_DONE -> done.incrementAndGet();
                case AiDocumentInsight.STATUS_FAILED -> failed.incrementAndGet();
                default -> skipped.incrementAndGet();
            }
            if (enqueued && done.get() + failed.get() + skipped.get() >= total.get()) {
                finishedAt = OffsetDateTime.now();
            }
        }

        InsightBackfillStatus snapshot() {
            boolean finished = enqueued && done.get() + failed.get() + skipped.get() >= total.get();
            return new InsightBackfillStatus(id, folderId, force,
                    finished ? InsightBackfillStatus.DONE : InsightBackfillStatus.RUNNING,
                    total.get(), done.get(), failed.get(), skipped.get(), startedAt,
                    finished ? (finishedAt != null ? finishedAt : OffsetDateTime.now()) : null);
        }
    }

    @PostConstruct
    void start() {
        int concurrency = Math.max(1, aiProperties.getInsights().getConcurrency());
        worker = queue.asFlux()
                .flatMap(task -> process(task)
                        .onErrorResume(e -> {
                            log.warn("[INSIGHTS] enrichment of {} failed unexpectedly: {}", task.documentId(), e.toString());
                            return Mono.empty();
                        }), concurrency)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
        log.info("[INSIGHTS] tier-2 enrichment worker started (concurrency={}, daily-limit={}, model={})",
                concurrency, aiProperties.getInsights().getDailyLimit(),
                aiProperties.getInsights().getModel() == null || aiProperties.getInsights().getModel().isBlank()
                        ? "chat model" : aiProperties.getInsights().getModel());
    }

    @PreDestroy
    void stop() {
        queue.tryEmitComplete();
        if (worker != null) {
            worker.dispose();
        }
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public void enqueue(Document document, String textHead) {
        if (document == null || document.getId() == null || document.getType() != DocumentType.FILE) {
            return;
        }
        submit(new Task(document.getId(), textHead, null));
    }

    private void submit(Task task) {
        Sinks.EmitResult result = queue.tryEmitNext(task);
        if (result == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
            queue.emitNext(task, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(2)));
        } else if (result.isFailure()) {
            log.warn("[INSIGHTS] could not queue enrichment of {}: {}", task.documentId(), result);
        }
    }

    @Override
    public Mono<InsightBackfillStatus> backfill(UUID folderId, boolean force) {
        Job job = new Job(folderId, force);
        jobs.put(job.id, job);
        // Enqueue on a worker thread so the caller gets the handle at once; the candidate query
        // is bounded and ordered most-recently-updated first
        store.findBackfillCandidates(folderId, force, PROMPT_VERSION, BACKFILL_LIMIT)
                .doOnNext(id -> {
                    job.total.incrementAndGet();
                    submit(new Task(id, null, job.id));
                })
                .doOnComplete(() -> {
                    job.enqueued = true;
                    if (job.total.get() == 0) {
                        job.finishedAt = OffsetDateTime.now();
                    }
                    log.info("[INSIGHTS] backfill {} queued {} document(s) (folder={}, force={})", job.id, job.total.get(), folderId, force);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(id -> { }, e -> {
                    log.error("[INSIGHTS] backfill {} failed to enumerate documents: {}", job.id, e.toString());
                    job.enqueued = true;
                    job.finishedAt = OffsetDateTime.now();
                });
        return Mono.just(job.snapshot());
    }

    @Override
    public Optional<InsightBackfillStatus> backfillStatus(UUID jobId) {
        return Optional.ofNullable(jobs.get(jobId)).map(Job::snapshot);
    }

    // ── the enrichment itself ───────────────────────────────────────────────

    private Mono<Void> process(Task task) {
        return documentRepository.findById(task.documentId())
                .switchIfEmpty(Mono.fromRunnable(() -> log.debug("[INSIGHTS] {} vanished before enrichment", task.documentId())))
                .flatMap(document -> {
                    if (document.getType() != DocumentType.FILE || Boolean.FALSE.equals(document.getActive())) {
                        return outcome(task, AiDocumentInsight.STATUS_SKIPPED, "not an active file");
                    }
                    long maxBytes = aiProperties.getInsights().getMaxFileSize() == null
                            ? Long.MAX_VALUE : aiProperties.getInsights().getMaxFileSize().toBytes();
                    if (document.getSize() != null && document.getSize() > maxBytes) {
                        return outcome(task, AiDocumentInsight.STATUS_SKIPPED, "file larger than the insights size limit");
                    }
                    if (!underDailyCap()) {
                        return outcome(task, AiDocumentInsight.STATUS_SKIPPED, "daily enrichment limit reached");
                    }
                    return store.markPending(document.getId())
                            .then(textFor(document, task.textHead()))
                            .defaultIfEmpty("")
                            .flatMap(text -> text.isBlank()
                                    ? outcome(task, AiDocumentInsight.STATUS_SKIPPED, "no extractable text")
                                    : enrich(task, document, text));
                });
    }

    private Mono<Void> enrich(Task task, Document document, String text) {
        return Mono.fromCallable(() -> {
                    ResolvedChat chat = model();
                    String answer = ChatClient.builder(chat.chatModel()).build().prompt()
                            .system(systemPrompt())
                            .user(userPrompt(document, text))
                            .options(ChatOptions.builder().temperature(0.0))
                            .call()
                            .content();
                    InsightResult result = InsightResult.parse(answer, aiProperties.getInsights().getCategories());
                    return Map.entry(chat, result);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(entry -> {
                    enrichedToday.incrementAndGet();
                    String modelName = entry.getKey().provider().toLowerCase(Locale.ROOT) + ":" + entry.getKey().model();
                    return store.saveEnrichment(document.getId(), entry.getValue(), modelName, PROMPT_VERSION)
                            .then(mirrorToIndex(document.getId(), entry.getValue()))
                            .then(finish(task, AiDocumentInsight.STATUS_DONE))
                            .doOnSuccess(v -> log.info("[INSIGHTS] '{}' ({}) -> {} [{}]", document.getName(), document.getId(),
                                    entry.getValue().category(), modelName));
                })
                .onErrorResume(e -> {
                    String reason = e instanceof IllegalArgumentException ? "model answer rejected: " + e.getMessage() : e.toString();
                    log.warn("[INSIGHTS] '{}' ({}) failed: {}", document.getName(), document.getId(), reason);
                    return outcome(task, AiDocumentInsight.STATUS_FAILED, reason);
                });
    }

    private Mono<Void> outcome(Task task, String status, String reason) {
        Mono<Void> mark = AiDocumentInsight.STATUS_FAILED.equals(status)
                ? store.markFailed(task.documentId(), reason)
                : store.markSkipped(task.documentId(), reason);
        return mark.then(finish(task, status));
    }

    private Mono<Void> finish(Task task, String status) {
        if (task.jobId() != null) {
            Job job = jobs.get(task.jobId());
            if (job != null) {
                job.finishOne(status);
            }
        }
        return Mono.empty();
    }

    private Mono<Void> mirrorToIndex(UUID documentId, InsightResult result) {
        IndexService indexService = indexServiceProvider.getIfAvailable();
        if (indexService == null) {
            return Mono.empty();
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(OpenSearchDocumentKey.category.toString(), result.category());
        if (result.summary() != null) fields.put(OpenSearchDocumentKey.summary.toString(), result.summary());
        if (result.language() != null) fields.put(OpenSearchDocumentKey.language.toString(), result.language());
        return indexService.updateIndexFields(documentId, fields)
                .onErrorResume(e -> {
                    log.debug("[INSIGHTS] index mirror failed for {}: {}", documentId, e.getMessage());
                    return Mono.empty();
                });
    }

    /** The text head: what the caller already had, else the index, else a fresh Tika pass on the file. */
    private Mono<String> textFor(Document document, String known) {
        int max = Math.max(500, aiProperties.getInsights().getMaxChars());
        if (known != null && !known.isBlank()) {
            return Mono.just(head(known, max));
        }
        IndexService indexService = indexServiceProvider.getIfAvailable();
        Mono<String> fromIndex = indexService == null ? Mono.empty()
                : indexService.getContent(document.getId()).onErrorResume(e -> Mono.empty());
        return fromIndex.map(text -> head(text, max))
                .switchIfEmpty(Mono.defer(() -> extract(document, max)));
    }

    private Mono<String> extract(Document document, int max) {
        try {
            Path tempFile = Files.createTempFile("insight-", ".tmp");
            return tikaService.processResource(tempFile, storageService.loadFile(document.getStoragePath()))
                    .reduce(new StringBuilder(), (sb, chunk) -> sb.length() < max ? sb.append(chunk) : sb)
                    .map(sb -> head(sb.toString(), max))
                    .doFinally(s -> {
                        try { Files.deleteIfExists(tempFile); } catch (IOException ignored) { }
                    })
                    .onErrorResume(e -> {
                        log.debug("[INSIGHTS] text extraction failed for {}: {}", document.getId(), e.getMessage());
                        return Mono.empty();
                    });
        } catch (IOException e) {
            return Mono.empty();
        }
    }

    private static String head(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private boolean underDailyCap() {
        LocalDate now = LocalDate.now();
        if (!now.equals(today)) {
            today = now;
            enrichedToday.set(0);
        }
        int limit = aiProperties.getInsights().getDailyLimit();
        return limit <= 0 || enrichedToday.get() < limit;
    }

    /** The insights model: the configured {@code provider:model} when set and buildable, else the chat model. */
    private ResolvedChat model() {
        ResolvedChat current = model;
        if (current != null) {
            return current;
        }
        String configured = aiProperties.getInsights().getModel();
        ResolvedChat resolved = fallbackChain.configuredModel(configured)
                .orElseGet(() -> resolver.resolve(null).block());
        if (resolved == null) {
            throw new IllegalStateException("no chat model available for document insights");
        }
        model = resolved;
        return resolved;
    }

    // ── prompt ──────────────────────────────────────────────────────────────

    String systemPrompt() {
        List<String> categories = aiProperties.getInsights().getCategories();
        return """
                You label documents for a document management system (%s).
                Given the beginning of a document's text and its file name, answer with ONE JSON object and nothing else:
                {"category": "<one of: %s>", "summary": "<one or two sentences, in the document's own language>", \
                "keywords": ["<up to 8 short keywords>"], "language": "<BCP-47 primary tag, e.g. fr>", \
                "entities": {"<key>": "<value>"}}
                Rules: the category MUST be one of the listed values ("other" when none fits); entities are the few \
                identifiers that matter for filing the document (client, supplier, invoice_number, contract_reference, \
                period, project, person) — omit what is absent; never invent facts; no Markdown, no commentary.
                """.formatted(PROMPT_MARKER, String.join(", ", categories == null ? List.of(InsightResult.OTHER) : categories));
    }

    String userPrompt(Document document, String text) {
        String name = document.getName() == null ? "" : document.getName();
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return "File name: " + name + "\nType: " + (extension.isEmpty() ? "unknown" : extension)
                + (document.getContentType() != null ? " (" + document.getContentType() + ")" : "")
                + "\n\nText (beginning):\n" + text;
    }
}
