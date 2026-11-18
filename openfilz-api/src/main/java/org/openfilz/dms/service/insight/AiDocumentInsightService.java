package org.openfilz.dms.service.insight;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.config.AiProperties.Insights.Classifier.Mode;
import org.openfilz.dms.dto.response.InsightBackfillStatus;
import org.openfilz.dms.entity.AiDocumentInsight;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.exception.AiNoModelException;
import org.openfilz.dms.enums.OpenSearchDocumentKey;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.IndexService;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.ai.AiFailoverPolicy;
import org.openfilz.dms.service.ai.AiFallbackChain;
import org.openfilz.dms.service.ai.ModelAnswers;
import org.openfilz.dms.service.ai.UserChatClientResolver;
import org.openfilz.dms.service.ai.UserChatClientResolver.ResolvedChat;
import org.openfilz.dms.service.impl.TikaService;
import org.openfilz.dms.service.insight.CategoryClassifier.CategoryPrediction;
import org.openfilz.dms.service.insight.InsightsPolicy.Verdict;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The real tier-2 insight service: one model call per document on a bounded queue.
 * <p>
 * Never on the upload path — {@link #enqueue} returns at once and the queue drains on its own
 * scheduler with {@code openfilz.ai.insights.local-concurrency} workers, of which at most
 * {@code openfilz.ai.insights.concurrency} call a model at once. The classifier mode is read per
 * document, so it may change at runtime. The text is the
 * head the indexing pass already extracted ({@code max-chars}); documents above
 * {@code max-file-size} or without text are SKIPPED, a model answer that is not the JSON
 * contract is FAILED (never a half row), and the daily cap turns the rest into SKIPPED rows
 * that the backfill picks up later. The model is the deployment's chat model unless
 * {@code openfilz.ai.insights.model} names a cheaper {@code provider:model}; BYOK is not
 * consulted, insights are deployment-level data.
 * <p>
 * Two seams sit in front of the model. The {@link CategoryTaxonomy} is the closed list the
 * prompt describes and the answer is validated against; the {@link InsightsPolicy} is asked,
 * per document and on the worker, whether the document may be enriched at all, whether a model
 * may read its text, and which kinds must never reach one — the core permits everything, an
 * extension narrows it. When the policy bars the model, the local {@link CategoryClassifier}
 * is the only thing allowed to answer, whatever the configured mode; when it bars some kinds,
 * the classifier screens the document first and a blocked kind is stored as a category-only
 * row without a model call. Neither case ever sends text to a model "just to check".
 */
@Slf4j
@Service
@Lazy
@Qualifier("aiDocumentInsightService")
public class AiDocumentInsightService implements DocumentInsightService {

    /**
     * Bump when the prompt or the output contract changes: a backfill with force re-enriches
     * older rows. 2 = the prompt describes every kind of the taxonomy (key — description)
     * instead of listing bare keys; the JSON contract is unchanged.
     */
    public static final int PROMPT_VERSION = 2;
    /** Marker the test configuration keys its stub on; also documents which prompt produced a row. */
    static final String PROMPT_MARKER = "INSIGHTS_V1";

    private static final int MAX_QUEUE = 20_000;
    /** At most one "no model, the local verdict is kept" line per this many milliseconds. */
    private static final long NO_MODEL_LOG_INTERVAL_MS = 60_000;
    private static final int BACKFILL_LIMIT = 10_000;
    /** Reason of the SKIPPED row of a document the daily limit left out; the backfill offers it again. */
    private static final String DAILY_LIMIT_REACHED = "daily enrichment limit reached";

    private final AiProperties aiProperties;
    private final DocumentInsightStore store;
    private final UserChatClientResolver resolver;
    private final AiFallbackChain fallbackChain;
    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final TikaService tikaService;
    private final ObjectProvider<IndexService> indexServiceProvider;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final InsightCompletionSignal signal;
    private final ObjectProvider<CategoryClassifier> classifierProvider;
    private final InsightsPolicy policy;
    private final CategoryTaxonomy taxonomy;

    private final Sinks.Many<Task> queue = Sinks.many().unicast().onBackpressureBuffer();
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger enrichedToday = new AtomicInteger();
    private volatile LocalDate today = LocalDate.now();
    private volatile Disposable worker;
    /**
     * The enrichments' own threads: a classification blocks (a model call, or an in-process
     * embedding), and it must not compete for the shared {@code boundedElastic} with the uploads
     * whose text it is enriching — a batch would otherwise slow down the very pipeline feeding it.
     */
    private volatile Scheduler scheduler;
    private volatile CachedModel model;
    /** Model calls in flight: {@code concurrency}, whatever the number of workers. */
    private volatile Semaphore modelPermits;
    /** The mode the last log line named: a runtime change is logged once, at the next document. */
    private final AtomicReference<Mode> loggedMode = new AtomicReference<>();
    private final AtomicLong lastNoModelLog = new AtomicLong();

    /**
     * The model in use and the {@link UserChatClientResolver#defaultModelVersion()} it was resolved
     * under. {@code pinned} = {@code openfilz.ai.insights.model}, which no runtime change replaces.
     */
    private record CachedModel(ResolvedChat chat, long version, boolean pinned) {}

    public AiDocumentInsightService(AiProperties aiProperties, DocumentInsightStore store,
                                    UserChatClientResolver resolver, AiFallbackChain fallbackChain,
                                    DocumentRepository documentRepository, StorageService storageService,
                                    TikaService tikaService, ObjectProvider<IndexService> indexServiceProvider,
                                    org.springframework.context.ApplicationEventPublisher events,
                                    InsightCompletionSignal signal, ObjectProvider<CategoryClassifier> classifierProvider,
                                    InsightsPolicy policy, CategoryTaxonomy taxonomy) {
        this.aiProperties = aiProperties;
        this.store = store;
        this.resolver = resolver;
        this.fallbackChain = fallbackChain;
        this.documentRepository = documentRepository;
        this.storageService = storageService;
        this.tikaService = tikaService;
        this.indexServiceProvider = indexServiceProvider;
        this.events = events;
        this.signal = signal;
        this.classifierProvider = classifierProvider;
        this.policy = policy;
        this.taxonomy = taxonomy;
    }

    /** One queued enrichment: the document to enrich, the text head when already known, the job it belongs to. */
    record Task(UUID documentId, String textHead, UUID jobId) {
    }

    /**
     * The enrichment stops here with a SKIPPED row: nothing failed, the policy or the
     * configuration decided that this document gets no verdict today (the backfill offers it
     * again). Thrown from the worker's callable so the reactive chain routes it like an outcome.
     */
    static final class SkipEnrichment extends RuntimeException {
        SkipEnrichment(String reason) {
            super(reason, null, false, false);
        }
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
        int concurrency = workerConcurrency();
        modelPermits = new Semaphore(Math.max(1, aiProperties.getInsights().getConcurrency()), true);
        loggedMode.set(classifierMode());
        scheduler = Schedulers.newBoundedElastic(concurrency, Integer.MAX_VALUE, "openfilz-insights", 60, true);
        worker = queue.asFlux()
                .flatMap(task -> process(task)
                        .onErrorResume(e -> {
                            log.warn("[INSIGHTS] enrichment of {} failed unexpectedly: {}", task.documentId(), e.toString());
                            return Mono.empty();
                        }), concurrency)
                .subscribeOn(scheduler)
                .subscribe();
        log.info("[INSIGHTS] tier-2 enrichment worker started (workers={}, model-calls={}, classifier={}, daily-limit={}, model={}, policy={})",
                concurrency, modelPermits.availablePermits(), loggedMode.get(), aiProperties.getInsights().getDailyLimit(),
                aiProperties.getInsights().getModel() == null || aiProperties.getInsights().getModel().isBlank()
                        ? "chat model" : aiProperties.getInsights().getModel(),
                policy.getClass().getSimpleName());
    }

    /**
     * How many documents are enriched at once. The mode may change at runtime, so the workers are
     * sized for the local classifier ({@code local-concurrency}): a classification is bound by CPU
     * alone, and an upload batch of hundreds of files would otherwise trickle through two at a
     * time. Model calls are bounded apart, by {@link #modelPermits} ({@code concurrency}, the
     * number of concurrent model calls the deployment is willing to pay for).
     */
    private int workerConcurrency() {
        AiProperties.Insights insights = aiProperties.getInsights();
        return Math.max(1, Math.max(insights.getLocalConcurrency(), insights.getConcurrency()));
    }

    /** Logs the classifier mode when it changed since the last line (a runtime setting may move it). */
    private void logModeChange(Mode mode) {
        Mode previous = loggedMode.getAndSet(mode);
        if (previous != mode) {
            log.info("[INSIGHTS] classifier mode is now {} (was {})", mode, previous);
        }
    }

    @PreDestroy
    void stop() {
        queue.tryEmitComplete();
        if (worker != null) {
            worker.dispose();
        }
        if (scheduler != null) {
            scheduler.dispose();
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
    public Mono<InsightBackfillStatus> backfill(UUID folderId, boolean force, String userEmail) {
        Job job = new Job(folderId, force);
        jobs.put(job.id, job);
        // Enqueue on a worker thread so the caller gets the handle at once; the candidate query
        // is bounded and ordered most-recently-updated first
        store.findBackfillCandidates(folderId, force, PROMPT_VERSION, BACKFILL_LIMIT, userEmail)
                .doOnNext(id -> {
                    job.total.incrementAndGet();
                    submit(new Task(id, null, job.id));
                })
                .doOnComplete(() -> {
                    job.enqueued = true;
                    if (job.total.get() == 0) {
                        job.finishedAt = OffsetDateTime.now();
                    }
                    log.info("[INSIGHTS] backfill {} queued {} document(s) (folder={}, force={}, by={})", job.id, job.total.get(), folderId, force, userEmail);
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

    /** One enrichment, start to terminal row. Package-private so the unit test drives it without the queue. */
    Mono<Void> process(Task task) {
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
                    logModeChange(classifierMode());
                    return verdictFor(document).flatMap(verdict -> {
                        if (!verdict.enrichmentAllowed()) {
                            return outcome(task, AiDocumentInsight.STATUS_SKIPPED, "disabled by policy" + reasonSuffix(verdict));
                        }
                        // The daily cap counts model calls: with a local classifier there is always an answer
                        if (classifierMode() == Mode.LLM && verdict.modelAllowed() && !underDailyCap()) {
                            return outcome(task, AiDocumentInsight.STATUS_SKIPPED, DAILY_LIMIT_REACHED);
                        }
                        return store.markPending(document.getId())
                                .then(textFor(document, task.textHead()))
                                .defaultIfEmpty("")
                                .flatMap(text -> text.isBlank()
                                        ? outcome(task, AiDocumentInsight.STATUS_SKIPPED, "no extractable text")
                                        : enrich(task, document, text, verdict));
                    });
                });
    }

    /**
     * The policy's answer for this document. A policy that fails to answer fails closed: the
     * document is treated as not enrichable now (SKIPPED, the backfill offers it again) — never
     * enriched on the assumption that everything is allowed.
     */
    private Mono<Verdict> verdictFor(Document document) {
        return Mono.defer(() -> policy.forDocument(document))
                .defaultIfEmpty(Verdict.permitAll())
                .onErrorResume(e -> {
                    log.warn("[INSIGHTS] policy lookup failed for {} — not enriching it now: {}", document.getId(), e.toString());
                    return Mono.just(new Verdict(false, false, java.util.Set.of(), "policy lookup failed: " + e.getMessage()));
                });
    }

    private static String reasonSuffix(Verdict verdict) {
        return verdict.reason() == null || verdict.reason().isBlank() ? "" : ": " + verdict.reason().trim();
    }

    private Mono<Void> enrich(Task task, Document document, String text, Verdict verdict) {
        return Mono.fromCallable(() -> {
                    LocalVerdict local = classifyLocally(document, text, verdict);
                    if (local.answer() != null) {
                        return local.answer();
                    }
                    ResolvedChat primary;
                    try {
                        primary = model();
                    } catch (AiNoModelException noModel) {
                        // auto with no model anywhere: the local verdict stands, as in learned mode,
                        // rather than a FAILED row. Only "no model": a provider error stays FAILED.
                        if (local.fallback() == null) {
                            throw noModel;
                        }
                        logNoModelFallback();
                        return local.fallback();
                    }
                    AtomicReference<ResolvedChat> used = new AtomicReference<>(primary);
                    // The answer cap: a looping small model stops there, a thinking model must fit its thoughts in it
                    int cap = aiProperties.getMaxAnswerTokens();
                    // One call, with the chat's failover: a 429 on the insights model is retried on
                    // the next candidate of the chain instead of leaving the row FAILED. The permit
                    // bounds model calls; this runs on the insights scheduler, where blocking is fine.
                    InsightResult result;
                    modelPermits.acquire();
                    try {
                        // The daily limit caps what the model costs, and this document passed it before
                        // waiting here with the other workers: checked again, and counted, under the
                        // permit, so the limit is exceeded by no more than the calls in flight.
                        if (!underDailyCap()) {
                            if (local.fallback() != null) {
                                return local.fallback();
                            }
                            throw new SkipEnrichment(DAILY_LIMIT_REACHED);
                        }
                        String answer = fallbackChain.callWithFailover(primary, "INSIGHTS", candidate -> {
                            used.set(candidate);
                            return ModelAnswers.text(ChatClient.builder(candidate.chatModel()).build().prompt()
                                    .system(systemPrompt())
                                    .user(userPrompt(document, text))
                                    .options(ChatOptions.builder().temperature(0.0).maxTokens(cap))
                                    .call()
                                    .chatResponse(), "INSIGHTS", cap);
                        });
                        try {
                            result = InsightResult.parse(answer, taxonomy.keys());
                        } catch (IllegalArgumentException e) {
                            // The answer itself goes to the log (not to the row) so the prompt can be tuned.
                            log.warn("[INSIGHTS] model answer rejected for '{}' ({}): {}, answer: {}", document.getName(),
                                    document.getId(), e.getMessage(), head(answer));
                            throw e;
                        }
                        enrichedToday.incrementAndGet();
                    } finally {
                        modelPermits.release();
                    }
                    return Map.entry(used.get().provider().toLowerCase(Locale.ROOT) + ":" + used.get().model(), result);
                })
                .subscribeOn(scheduler)
                .flatMap(entry -> {
                    String modelName = entry.getKey();
                    InsightResult answer = entry.getValue();
                    return store.saveEnrichment(document.getId(), answer, modelName, PROMPT_VERSION)
                            .defaultIfEmpty(answer.category() == null ? InsightResult.OTHER : answer.category())
                            .flatMap(storedCategory -> {
                                // The row keeps a kind the user set meanwhile: the index and the event follow the row
                                InsightResult stored = storedCategory.equals(answer.category()) ? answer
                                        : new InsightResult(storedCategory, answer.summary(), answer.keywords(),
                                                answer.language(), answer.entities());
                                if (stored != answer) {
                                    log.info("[INSIGHTS] '{}' ({}): kept the kind the user set ({}) over {}'s {}",
                                            document.getName(), document.getId(), storedCategory, modelName, answer.category());
                                }
                                // The row is committed: whoever waits on it (smart filing) may read it now.
                                signal.complete(document.getId());
                                return mirrorToIndex(document.getId(), stored)
                                        .then(finish(task, AiDocumentInsight.STATUS_DONE))
                                        .doOnSuccess(v -> publishReady(document, stored))
                                        .doOnSuccess(v -> log.info("[INSIGHTS] '{}' ({}) -> {} [{}]", document.getName(),
                                                document.getId(), stored.category(), modelName));
                            });
                })
                .onErrorResume(SkipEnrichment.class, skip -> {
                    log.info("[INSIGHTS] '{}' ({}) not enriched: {}", document.getName(), document.getId(), skip.getMessage());
                    return outcome(task, AiDocumentInsight.STATUS_SKIPPED, skip.getMessage());
                })
                .onErrorResume(e -> {
                    String reason = e instanceof IllegalArgumentException
                            ? "model answer rejected: " + e.getMessage()
                            : AiFailoverPolicy.describe(e);
                    log.warn("[INSIGHTS] '{}' ({}) failed: {}", document.getName(), document.getId(), reason);
                    return outcome(task, AiDocumentInsight.STATUS_FAILED, reason);
                });
    }

    /**
     * The category from the local classifier when the mode — or the policy — wants it. By mode:
     * always in {@code prototype} / {@code learned}, in {@code auto} when it is sure enough (or
     * the daily model cap is spent), never in {@code llm}. By policy, over the mode: when the
     * model is barred for this document the classifier's verdict is final whatever the mode;
     * when some kinds are barred from the model, the classifier screens the document before a
     * model call and a barred kind is stored as it is, without the call. A category-only
     * insight: no summary, keywords or entities. No answer = ask the model, with, in {@code auto}
     * mode, the local verdict to keep when no model exists; {@link SkipEnrichment}
     * = the policy wants a classifier and there is none, so the document is left alone (its text
     * never reaches a model as a fallback).
     */
    private LocalVerdict classifyLocally(Document document, String text, Verdict verdict) {
        Mode mode = classifierMode();
        boolean modelBarred = !verdict.modelAllowed();
        boolean screening = !verdict.blockedCategories().isEmpty();
        if (mode == Mode.LLM && !modelBarred && !screening) {
            return LocalVerdict.ASK_MODEL;
        }
        CategoryClassifier classifier = classifierProvider.getIfAvailable();
        if (classifier == null) {
            if (modelBarred) {
                throw new SkipEnrichment("the model may not be used for this document and no local classifier is configured");
            }
            if (screening) {
                throw new SkipEnrichment("the policy keeps some kinds away from the model and no local classifier can screen the document");
            }
            log.warn("[INSIGHTS] classifier mode {} but no CategoryClassifier bean — asking the model", mode);
            return LocalVerdict.ASK_MODEL;
        }
        AiProperties.Insights.Classifier config = aiProperties.getInsights().getClassifier();
        CategoryPrediction prediction = classifier.classify(document.getId(), document.getName(),
                head(text, Math.max(200, config.getMaxChars())));
        if (modelBarred) {
            log.debug("[INSIGHTS] '{}' ({}): the policy bars the model{} — {} says {} at {}", document.getName(),
                    document.getId(), reasonSuffix(verdict), classifier.name(), prediction.category(), fmt(prediction.confidence()));
            return LocalVerdict.of(Map.entry(classifier.name(), categoryOnly(prediction.category())));
        }
        if (mode != Mode.LLM && acceptLocal(mode, prediction.confidence(), config.getMinConfidence(), underDailyCap())) {
            log.debug("[INSIGHTS] '{}' ({}): {} says {} at {}", document.getName(), document.getId(), classifier.name(),
                    prediction.category(), fmt(prediction.confidence()));
            return LocalVerdict.of(Map.entry(classifier.name(), categoryOnly(prediction.category())));
        }
        // A model call is next: the policy may keep this kind of document away from it
        if (verdict.blocks(prediction.category())) {
            log.info("[INSIGHTS] '{}' ({}) looks like a {} ({} at {}) — a kind the policy keeps away from the model; "
                            + "stored as category-only", document.getName(), document.getId(), prediction.category(),
                    classifier.name(), fmt(prediction.confidence()));
            return LocalVerdict.of(Map.entry(InsightsPolicy.SCREENED_MODEL_PREFIX + classifier.name(),
                    categoryOnly(prediction.category())));
        }
        log.debug("[INSIGHTS] '{}' ({}): {} says {} at {} — asking the model", document.getName(),
                document.getId(), classifier.name(), prediction.category(), fmt(prediction.confidence()));
        return mode == Mode.AUTO
                ? new LocalVerdict(null, Map.entry(classifier.name(), categoryOnly(prediction.category())))
                : LocalVerdict.ASK_MODEL;
    }

    /**
     * What the local classifier decided: a final {@code answer}, or none (ask the model) with, in
     * {@code auto} mode, the {@code fallback} to store when no model exists at all.
     */
    private record LocalVerdict(Map.Entry<String, InsightResult> answer, Map.Entry<String, InsightResult> fallback) {
        static final LocalVerdict ASK_MODEL = new LocalVerdict(null, null);

        static LocalVerdict of(Map.Entry<String, InsightResult> answer) {
            return new LocalVerdict(answer, null);
        }
    }

    /** At most once a minute: auto mode keeps the local verdict because no model exists. */
    private void logNoModelFallback() {
        long now = System.currentTimeMillis();
        long last = lastNoModelLog.get();
        if (now - last >= NO_MODEL_LOG_INTERVAL_MS && lastNoModelLog.compareAndSet(last, now)) {
            log.info("[INSIGHTS] classifier mode auto but no chat model exists: the local verdict is kept "
                    + "(category only) until a model is configured");
        }
    }

    private static InsightResult categoryOnly(String category) {
        return new InsightResult(category, null, List.of(), null, Map.of());
    }

    /** Is a local verdict final? In {@code prototype} and {@code learned} modes always; in {@code auto} mode when sure, or when no model call is left today. */
    static boolean acceptLocal(Mode mode, double confidence, double minConfidence, boolean modelAllowed) {
        if (mode == null || mode == Mode.LLM) {
            return false;
        }
        return mode == Mode.PROTOTYPE || mode == Mode.LEARNED || confidence >= minConfidence || !modelAllowed;
    }

    private Mode classifierMode() {
        Mode mode = aiProperties.getInsights().getClassifier().getMode();
        return mode == null ? Mode.LLM : mode;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** The first 300 characters of a model answer, on one line, for the log. */
    private static String head(String answer) {
        if (answer == null) return "null";
        String flat = answer.replace('\n', ' ').replace('\r', ' ');
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
    }

    private void publishReady(Document document, InsightResult result) {
        try {
            events.publishEvent(new org.openfilz.dms.event.DocumentInsightsReadyEvent(document.getId(), document.getName(),
                    result.category(), result.summary(), result.language(), result.keywords(), result.entities()));
        } catch (Exception e) {
            log.debug("[INSIGHTS] event publication failed for {}: {}", document.getId(), e.getMessage());
        }
    }

    private Mono<Void> outcome(Task task, String status, String reason) {
        Mono<Void> mark = AiDocumentInsight.STATUS_FAILED.equals(status)
                ? store.markFailed(task.documentId(), reason)
                : store.markSkipped(task.documentId(), reason);
        return mark.doOnSuccess(v -> signal.complete(task.documentId())).then(finish(task, status));
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

    /**
     * The insights model: {@code openfilz.ai.insights.model} when set (kept for the life of the
     * process), else the default model, resolved again whenever the runtime model source moved
     * (set, changed or removed at runtime), so a removed model is never called again.
     */
    ResolvedChat model() {
        // Read before resolving: a change landing during the resolution leaves an older version on
        // the cached entry, so the next call resolves again.
        long version = resolver.defaultModelVersion();
        CachedModel current = model;
        if (current != null && (current.pinned() || current.version() == version)) {
            return current.chat();
        }
        String configured = aiProperties.getInsights().getModel();
        Optional<ResolvedChat> pinned = fallbackChain.configuredModel(configured);
        ResolvedChat resolved = pinned.orElseGet(() -> resolver.resolve(null).block());
        if (resolved == null) {
            model = null;
            throw new AiNoModelException("no chat model available for document insights", resolver.isUserSettingsEnabled());
        }
        model = new CachedModel(resolved, version, pinned.isPresent());
        return resolved;
    }

    // ── prompt ──────────────────────────────────────────────────────────────

    /** The system prompt over the taxonomy as it is now — an extension's taxonomy may change between two documents. */
    String systemPrompt() {
        return InsightPrompts.system(PROMPT_MARKER, taxonomy.categories());
    }

    String userPrompt(Document document, String text) {
        return InsightPrompts.user(document.getName(), document.getContentType(), text);
    }
}
