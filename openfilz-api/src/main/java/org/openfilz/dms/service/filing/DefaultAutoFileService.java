package org.openfilz.dms.service.filing;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.dto.request.MoveRequest;
import org.openfilz.dms.dto.request.ReorganizationPlanRequest;
import org.openfilz.dms.dto.response.AutoFileJobView;
import org.openfilz.dms.dto.response.AutoFileTicket;
import org.openfilz.dms.dto.response.DocumentInsightView;
import org.openfilz.dms.dto.response.FilingOutcome;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.entity.AiDocumentInsight;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.event.DocumentFiledEvent;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.IndexService;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.ai.AiAccessPolicy;
import org.openfilz.dms.service.ai.AiFailoverPolicy;
import org.openfilz.dms.service.ai.AiFallbackChain;
import org.openfilz.dms.service.ai.AiToolRolePolicy;
import org.openfilz.dms.service.ai.ReorganizationInventoryCache;
import org.openfilz.dms.service.ai.ReorganizationPlanService;
import org.openfilz.dms.service.ai.ReorganizationPlanService.Caller;
import org.openfilz.dms.service.ai.ReorganizationPlanService.FilingApplyResult;
import org.openfilz.dms.service.ai.ToolCapability;
import org.openfilz.dms.service.ai.UserChatClientResolver;
import org.openfilz.dms.service.ai.UserChatClientResolver.ResolvedChat;
import org.openfilz.dms.service.filing.AutoFileDecision.ModelAnswer;
import org.openfilz.dms.service.filing.AutoFileDecision.Neighbour;
import org.openfilz.dms.service.filing.AutoFileDecision.Vote;
import org.openfilz.dms.service.impl.TikaService;
import org.openfilz.dms.service.insight.DocumentInsightService;
import org.openfilz.dms.service.insight.DocumentInsightStore;
import org.openfilz.dms.service.insight.InsightCompletionSignal;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The real smart-filing service (design §13). One document goes through:
 * <ol>
 *   <li><b>eligibility</b>: an active FILE the caller may modify, with a live session;</li>
 *   <li><b>stage 1, the neighbour vote</b>: the vector store's nearest documents, resolved to
 *       their <em>live</em> folders (never the folder stored in chunk metadata), inside the scope
 *       (the folder the document was dropped in; root = the whole library) and writable — the
 *       leading folder wins when it holds {@code neighbour-min-share} of the weight;</li>
 *   <li><b>stage 2, the model</b>: only when the vote is inconclusive — the folder inventory of
 *       the scope plus the document's insight; a new folder only above
 *       {@code new-folder-min-confidence} and when the user allows it;</li>
 *   <li><b>stage 3</b>: a one-item reorganisation plan (origin AUTO_FILE) validated and applied
 *       through {@link ReorganizationPlanService}: same permission, name-clash and no-op checks
 *       as a chat proposal, same audited move.</li>
 * </ol>
 * Below the thresholds the document stays where it was (SKIPPED, with the reason). Jobs are
 * in-memory; the filing records are the plans, so undo works from either.
 */
@Slf4j
@Service
@Lazy
@Qualifier("defaultAutoFileService")
public class DefaultAutoFileService implements AutoFileService, UserInfoService {

    static final String PROMPT_MARKER = "AUTOFILE_V1";
    private static final int QUERY_CHARS = 2000;
    private static final int MODEL_TEXT_CHARS = 1500;
    private static final int MAX_JOBS = 5_000;

    private final AiProperties aiProperties;
    private final DocumentRepository documentRepository;
    private final DocumentService documentService;
    private final AiAccessPolicy accessPolicy;
    private final AiToolRolePolicy rolePolicy;
    private final ReorganizationPlanService planService;
    private final ReorganizationInventoryCache inventoryCache;
    private final DocumentInsightStore insightStore;
    private final DocumentInsightService insightService;
    private final InsightCompletionSignal insightSignal;
    private final AiPreferencesService preferences;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectProvider<IndexService> indexServiceProvider;
    private final UserChatClientResolver resolver;
    private final AiFallbackChain fallbackChain;
    private final TikaService tikaService;
    private final StorageService storageService;
    private final ApplicationEventPublisher events;

    private final Sinks.Many<Task> queue = Sinks.many().unicast().onBackpressureBuffer();
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private volatile Disposable worker;
    private volatile ResolvedChat model;
    /** Serialises the stage-2 applies that create a folder — see the model stage below. */
    private final Object createFolderLock = new Object();

    public DefaultAutoFileService(AiProperties aiProperties, DocumentRepository documentRepository,
                                  DocumentService documentService, AiAccessPolicy accessPolicy, AiToolRolePolicy rolePolicy,
                                  ReorganizationPlanService planService, ReorganizationInventoryCache inventoryCache,
                                  DocumentInsightStore insightStore, DocumentInsightService insightService,
                                  InsightCompletionSignal insightSignal, AiPreferencesService preferences, ObjectProvider<VectorStore> vectorStoreProvider,
                                  ObjectProvider<IndexService> indexServiceProvider, UserChatClientResolver resolver,
                                  AiFallbackChain fallbackChain, TikaService tikaService, StorageService storageService,
                                  ApplicationEventPublisher events) {
        this.aiProperties = aiProperties;
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.accessPolicy = accessPolicy;
        this.rolePolicy = rolePolicy;
        this.planService = planService;
        this.inventoryCache = inventoryCache;
        this.insightStore = insightStore;
        this.insightService = insightService;
        this.insightSignal = insightSignal;
        this.preferences = preferences;
        this.vectorStoreProvider = vectorStoreProvider;
        this.indexServiceProvider = indexServiceProvider;
        this.resolver = resolver;
        this.fallbackChain = fallbackChain;
        this.tikaService = tikaService;
        this.storageService = storageService;
        this.events = events;
    }

    private record Task(UUID documentId, Caller caller, boolean allowNewFolders, UUID jobId) {
    }

    /** One upload batch or on-demand request; items keep the outcome per document. */
    private static final class Job {
        final UUID id = UUID.randomUUID();
        final String createdBy;
        final OffsetDateTime createdAt = OffsetDateTime.now();
        final Map<UUID, FilingOutcome> items = new ConcurrentHashMap<>();
        final List<UUID> order;
        volatile String status = AutoFileJobView.RUNNING;
        volatile OffsetDateTime finishedAt;

        Job(String createdBy, List<UUID> documentIds) {
            this.createdBy = createdBy;
            this.order = List.copyOf(documentIds);
            documentIds.forEach(id -> items.put(id, new FilingOutcome(id, null, FilingOutcome.PENDING, null, null,
                    null, null, FilingOutcome.STAGE_NONE, null, null, null, null)));
        }

        void update(FilingOutcome outcome) {
            items.put(outcome.documentId(), outcome);
            boolean allDone = items.values().stream().noneMatch(o -> FilingOutcome.PENDING.equals(o.status()));
            if (allDone && AutoFileJobView.RUNNING.equals(status)) {
                status = AutoFileJobView.DONE;
                finishedAt = OffsetDateTime.now();
            }
        }

        AutoFileJobView view() {
            List<FilingOutcome> list = order.stream().map(items::get).filter(Objects::nonNull).toList();
            int filed = 0, skipped = 0, failed = 0, pending = 0;
            for (FilingOutcome o : list) {
                switch (o.status()) {
                    case FilingOutcome.FILED -> filed++;
                    case FilingOutcome.SKIPPED -> skipped++;
                    case FilingOutcome.FAILED -> failed++;
                    case FilingOutcome.PENDING -> pending++;
                    default -> { }
                }
            }
            return new AutoFileJobView(id, createdBy, status, list.size(), filed, skipped, failed, pending, list, createdAt, finishedAt);
        }
    }

    @PostConstruct
    void start() {
        int concurrency = Math.max(1, aiProperties.getAutoFile().getConcurrency());
        worker = queue.asFlux()
                .flatMap(task -> Mono.fromCallable(() -> runTask(task))
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorResume(e -> {
                            log.warn("[AUTOFILE] task for {} failed unexpectedly: {}", task.documentId(), e.toString());
                            record(task, new FilingOutcome(task.documentId(), null, FilingOutcome.FAILED, null, null, null, null,
                                    FilingOutcome.STAGE_NONE, null, e.toString(), null, OffsetDateTime.now()));
                            return Mono.empty();
                        }), concurrency)
                .subscribe();
        log.info("[AUTOFILE] smart filing worker started (concurrency={}, new folders allowed={}, max per batch={})",
                concurrency, aiProperties.getAutoFile().isAllowNewFolders(), aiProperties.getAutoFile().getMaxPerBatch());
    }

    @PreDestroy
    void stop() {
        queue.tryEmitComplete();
        if (worker != null) worker.dispose();
    }

    @Override
    public boolean isActive() {
        return true;
    }

    // ── entry points ────────────────────────────────────────────────────────

    @Override
    public Mono<List<UploadResponse>> afterUpload(List<UploadResponse> responses, Boolean autoFileParam) {
        List<UUID> ids = responses.stream().filter(r -> !r.isError() && r.id() != null).map(UploadResponse::id).toList();
        if (ids.isEmpty() || Boolean.FALSE.equals(autoFileParam)) {
            return Mono.just(responses);
        }
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> Optional.ofNullable(ctx.getAuthentication()))
                .defaultIfEmpty(Optional.empty())
                .flatMap(authentication -> {
                    // No principal (no-auth mode) = the anonymous user, the same identity the
                    // preferences endpoint saves under
                    String email = authentication.map(this::emailOf).filter(e -> e != null && !e.isBlank())
                            .orElse(UserInfoService.ANONYMOUS_USER);
                    Mono<Boolean> enabled = autoFileParam != null ? Mono.just(autoFileParam) : preferences.autoFileEnabled(email);
                    return enabled.flatMap(on -> {
                        if (!on) {
                            return Mono.just(responses);
                        }
                        return preferences.newFoldersAllowed(email).map(newFolders -> {
                            AutoFileJobView job = schedule(ids, new Caller(email, authentication.orElse(null)), newFolders);
                            AutoFileTicket ticket = new AutoFileTicket(job.jobId(), job.status());
                            return responses.stream().map(r -> r.isError() || r.id() == null ? r : r.withAutoFile(ticket)).toList();
                        });
                    });
                });
    }

    @Override
    public AutoFileJobView schedule(List<UUID> documentIds, Caller caller, Boolean allowNewFolders) {
        List<UUID> ids = documentIds == null ? List.of() : documentIds.stream().filter(Objects::nonNull).distinct().toList();
        int cap = Math.max(1, aiProperties.getAutoFile().getMaxPerBatch());
        List<UUID> accepted = ids.size() > cap ? ids.subList(0, cap) : ids;
        boolean newFolders = allowNewFolders != null ? allowNewFolders && aiProperties.getAutoFile().isAllowNewFolders()
                : Boolean.TRUE.equals(preferences.newFoldersAllowed(caller.email()).block());
        Job job = new Job(createdBy(caller), accepted);
        if (jobs.size() >= MAX_JOBS) {
            jobs.entrySet().removeIf(e -> !AutoFileJobView.RUNNING.equals(e.getValue().status)
                    && e.getValue().createdAt.isBefore(OffsetDateTime.now().minusHours(6)));
        }
        jobs.put(job.id, job);
        if (ids.size() > cap) {
            ids.subList(cap, ids.size()).forEach(id -> job.items.put(id, new FilingOutcome(id, null, FilingOutcome.SKIPPED,
                    null, null, null, null, FilingOutcome.STAGE_NONE, null,
                    "beyond the " + cap + " documents a batch may file", null, OffsetDateTime.now())));
        }
        if (accepted.isEmpty()) {
            job.status = AutoFileJobView.DONE;
            job.finishedAt = OffsetDateTime.now();
            return job.view();
        }
        accepted.forEach(id -> submit(new Task(id, caller, newFolders, job.id)));
        log.info("[AUTOFILE] job {} queued {} document(s) for {}", job.id, accepted.size(), caller.email());
        return job.view();
    }

    @Override
    public FilingOutcome fileNow(UUID documentId, Caller caller, Boolean allowNewFolders) {
        boolean newFolders = allowNewFolders != null ? allowNewFolders && aiProperties.getAutoFile().isAllowNewFolders()
                : Boolean.TRUE.equals(preferences.newFoldersAllowed(caller.email()).block());
        FilingOutcome outcome = file(documentId, caller, newFolders);
        log.info("[AUTOFILE] {} ({}) -> {}: {}", outcome.name(), documentId, outcome.status(), outcome.reason());
        return outcome;
    }

    @Override
    public Optional<AutoFileJobView> job(UUID jobId, Caller caller) {
        Job job = jobId == null ? null : jobs.get(jobId);
        if (job == null || !job.createdBy.equalsIgnoreCase(createdBy(caller))) {
            return Optional.empty();
        }
        return Optional.of(job.view());
    }

    @Override
    public AutoFileJobView undo(UUID jobId, Caller caller) {
        Job job = jobs.get(jobId);
        if (job == null || !job.createdBy.equalsIgnoreCase(createdBy(caller))) {
            throw new IllegalArgumentException("Unknown filing job");
        }
        for (FilingOutcome item : new ArrayList<>(job.items.values())) {
            if (FilingOutcome.FILED.equals(item.status())) {
                job.items.put(item.documentId(), moveBack(item, caller));
            }
        }
        job.status = AutoFileJobView.UNDONE;
        job.finishedAt = OffsetDateTime.now();
        inventoryCache.invalidate(caller.email());
        return job.view();
    }

    @Override
    public Mono<FilingOutcome> lastFiling(UUID documentId, Caller caller) {
        return Mono.fromCallable(() -> {
                    if (documentId == null || !Boolean.TRUE.equals(accessPolicy.canRead(documentId, caller.email()).block())) {
                        return Optional.<FilingOutcome>empty();
                    }
                    return planService.latestFiling(documentId);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(Mono::justOrEmpty);
    }

    @Override
    public Mono<FilingOutcome> undoFiling(UUID planId, Caller caller) {
        return Mono.fromCallable(() -> {
                    FilingOutcome filing = planService.filingOf(planId, caller)
                            .orElseThrow(() -> new IllegalArgumentException("Unknown filing"));
                    if (!FilingOutcome.FILED.equals(filing.status())) {
                        return filing;
                    }
                    FilingOutcome undone = moveBack(filing, caller);
                    inventoryCache.invalidate(caller.email());
                    return undone;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ── the pipeline ────────────────────────────────────────────────────────

    private FilingOutcome runTask(Task task) {
        FilingOutcome outcome = file(task.documentId(), task.caller(), task.allowNewFolders());
        record(task, outcome);
        log.info("[AUTOFILE] job {}: {} ({}) -> {} [{}]: {}", task.jobId(), outcome.name(), task.documentId(),
                outcome.status(), outcome.stage(), outcome.reason());
        return outcome;
    }

    private void record(Task task, FilingOutcome outcome) {
        Job job = task.jobId() == null ? null : jobs.get(task.jobId());
        if (job != null) {
            job.update(outcome);
        }
    }

    /** The whole pipeline for one document; blocking, on a worker or tool thread. */
    FilingOutcome file(UUID documentId, Caller caller, boolean allowNewFolders) {
        Document document = blockWithAuth(documentRepository.findByIdAndActive(documentId, true), caller);
        if (document == null) {
            return outcome(documentId, null, FilingOutcome.FAILED, null, null, FilingOutcome.STAGE_NONE, null,
                    "document not found", null);
        }
        String from = planService.pathOf(document.getParentId(), caller);
        if (document.getType() != DocumentType.FILE) {
            return outcome(documentId, document.getName(), FilingOutcome.SKIPPED, document.getParentId(), from,
                    FilingOutcome.STAGE_NONE, null, "only files are filed", null);
        }
        if (caller.authentication() instanceof JwtAuthenticationToken jwt && jwt.getToken().getExpiresAt() != null
                && Instant.now().isAfter(jwt.getToken().getExpiresAt())) {
            return outcome(documentId, document.getName(), FilingOutcome.SKIPPED, document.getParentId(), from,
                    FilingOutcome.STAGE_NONE, null, "the session expired before the document could be filed", null);
        }
        if (rolePolicy != null && !rolePolicy.isAllowed(caller.authentication(), ToolCapability.DOCUMENT_WRITE)) {
            return outcome(documentId, document.getName(), FilingOutcome.SKIPPED, document.getParentId(), from,
                    FilingOutcome.STAGE_NONE, null, "your role does not allow moving documents", null);
        }
        if (!Boolean.TRUE.equals(accessPolicy.canModify(documentId, caller.email()).block())) {
            return outcome(documentId, document.getName(), FilingOutcome.SKIPPED, document.getParentId(), from,
                    FilingOutcome.STAGE_NONE, null, "you cannot move this document", null);
        }

        UUID scopeRoot = document.getParentId();
        DocumentInsightView insight = awaitInsight(documentId);
        String text = textHead(document);
        if (text == null || text.isBlank()) {
            return outcome(documentId, document.getName(), FilingOutcome.SKIPPED, document.getParentId(), from,
                    FilingOutcome.STAGE_NONE, null, "no extractable text to compare with other documents", null);
        }

        // Stage 1 — the neighbour vote
        AiProperties.AutoFile config = aiProperties.getAutoFile();
        NeighbourScan scan = neighbours(document, scopeRoot, text, caller);
        List<Neighbour> neighbours = scan.votes();
        Optional<Vote> vote = AutoFileDecision.vote(neighbours, config.getNeighbourMinShare(), config.getNeighbourMinSimilarity());
        if (vote.isPresent()) {
            Vote winner = vote.get();
            if (Objects.equals(winner.folderId(), document.getParentId())) {
                return outcome(documentId, document.getName(), FilingOutcome.SKIPPED, document.getParentId(), from,
                        FilingOutcome.STAGE_NEIGHBOURS, winner.share(),
                        "already in the folder where its " + winner.documents() + " closest documents live", null);
            }
            String relative = planService.relativePath(winner.folderId(), scopeRoot, caller);
            String reason = "Similar to " + winner.documents() + " document" + (winner.documents() == 1 ? "" : "s")
                    + " in " + planService.pathOf(winner.folderId(), caller);
            return applyMove(document, from, scopeRoot, relative, FilingOutcome.STAGE_NEIGHBOURS, winner.share(), reason, caller);
        }

        // Stage 2 — the model
        String raw;
        try {
            raw = askModel(document, scopeRoot, insight, text, scan.unfiledSiblings(), caller, allowNewFolders);
        } catch (Exception e) {
            // The model could not be asked at all (quota, outage, key), even through the fallback
            // chain: no decision was taken, so the outcome is FAILED — file it again later from
            // the selection — with the provider's own message rather than Spring AI's wrapper.
            String cause = AiFailoverPolicy.describe(e);
            log.warn("[AUTOFILE] the model could not be asked for '{}' ({}): {}", document.getName(), documentId, cause);
            return outcome(documentId, document.getName(), FilingOutcome.FAILED, document.getParentId(), from,
                    FilingOutcome.STAGE_MODEL, null, "the model could not be asked (" + cause + ")", null);
        }
        if (raw == null) {
            return outcome(documentId, document.getName(), FilingOutcome.SKIPPED, document.getParentId(), from,
                    FilingOutcome.STAGE_MODEL, null, neighbours.isEmpty()
                            ? "no similar documents yet and no model to ask" : "no folder holds enough of its closest documents", null);
        }
        ModelAnswer answer;
        try {
            answer = ModelAnswer.parse(raw);
        } catch (IllegalArgumentException e) {
            // The model answered, but not with the contract: not a decision we can act on, so the
            // document stays where it is. The answer goes to the log so the prompt can be tuned.
            log.warn("[AUTOFILE] model answer rejected for '{}' ({}): {} — answer: {}", document.getName(), documentId,
                    e.getMessage(), head(raw));
            return outcome(documentId, document.getName(), FilingOutcome.SKIPPED, document.getParentId(), from,
                    FilingOutcome.STAGE_MODEL, null, "model answer rejected (" + e.getMessage() + ")", null);
        }
        String target = answer.target() == null ? "" : answer.target().trim();
        if (answer.confidence() < config.getLlmMinConfidence()) {
            return outcome(documentId, document.getName(), FilingOutcome.SKIPPED, document.getParentId(), from,
                    FilingOutcome.STAGE_MODEL, answer.confidence(), "the model was not confident enough ("
                            + String.format(Locale.ROOT, "%.2f", answer.confidence()) + ")", null);
        }
        if (target.isEmpty() || target.equals(".") || target.equals("/")) {
            // The scope root is where the document already lies, unfiled: the model saw nothing
            // better than leaving it there, which is a skip, not a filing.
            return outcome(documentId, document.getName(), FilingOutcome.SKIPPED, document.getParentId(), from,
                    FilingOutcome.STAGE_MODEL, answer.confidence(), "the model found no folder for it"
                            + (answer.reason() == null || answer.reason().isBlank() ? "" : " (" + answer.reason() + ")"), null);
        }
        boolean exists = planService.folderExists(scopeRoot, target, caller);
        if (!exists) {
            int newDepth = planService.missingDepth(scopeRoot, target, caller);
            if (!allowNewFolders) {
                return outcome(documentId, document.getName(), FilingOutcome.SKIPPED, document.getParentId(), from,
                        FilingOutcome.STAGE_MODEL, answer.confidence(), "would need a new folder (" + target + ") and creating folders is off", null);
            }
            if (answer.confidence() < config.getNewFolderMinConfidence()) {
                return outcome(documentId, document.getName(), FilingOutcome.SKIPPED, document.getParentId(), from,
                        FilingOutcome.STAGE_MODEL, answer.confidence(), "not confident enough to create a new folder (" + target + ")", null);
            }
            if (newDepth > config.getNewFolderMaxDepth()) {
                return outcome(documentId, document.getName(), FilingOutcome.SKIPPED, document.getParentId(), from,
                        FilingOutcome.STAGE_MODEL, answer.confidence(), "the proposed folder is too deep (" + target + ")", null);
            }
        }
        String reason = answer.reason() == null || answer.reason().isBlank() ? "Chosen by the model" : answer.reason();
        if (exists) {
            return applyMove(document, from, scopeRoot, target, FilingOutcome.STAGE_MODEL, answer.confidence(), reason, caller);
        }
        // Two documents of one batch, filed in parallel, may both propose the same new folder:
        // serialise the creating applies so the second one finds the folder the first created
        // (the plan resolves existing folders by name before creating) instead of clashing on it.
        synchronized (createFolderLock) {
            return applyMove(document, from, scopeRoot, target, FilingOutcome.STAGE_MODEL, answer.confidence(), reason, caller);
        }
    }

    private FilingOutcome applyMove(Document document, String from, UUID scopeRoot, String relativeTarget, String stage,
                                    double confidence, String reason, Caller caller) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("stage", stage);
        details.put("confidence", confidence);
        details.put("reason", reason);
        details.put("from", document.getParentId() == null ? null : document.getParentId().toString());
        details.put("fromPath", from);
        ReorganizationPlanRequest request = new ReorganizationPlanRequest(
                scopeRoot == null ? null : scopeRoot.toString(),
                List.of(new ReorganizationPlanRequest.Move(document.getId().toString(), relativeTarget)),
                null, reason);
        FilingApplyResult result = planService.fileDocument(request, document.getId(), caller, details);
        inventoryCache.invalidate(caller.email());
        FilingOutcome outcome = outcome(document.getId(), document.getName(), result.status(), document.getParentId(), from,
                stage, confidence, result.reason() != null ? result.reason() : reason, result.planId());
        if (FilingOutcome.FILED.equals(result.status())) {
            outcome = new FilingOutcome(document.getId(), document.getName(), FilingOutcome.FILED, document.getParentId(), from,
                    result.targetId(), result.targetPath(), stage, confidence, reason, result.planId(), OffsetDateTime.now());
            try {
                events.publishEvent(new DocumentFiledEvent(document.getId(), document.getName(), document.getParentId(),
                        result.targetId(), result.targetPath(), stage, confidence, reason, caller.email()));
            } catch (Exception e) {
                log.debug("[AUTOFILE] event publication failed: {}", e.getMessage());
            }
        }
        return outcome;
    }

    private FilingOutcome moveBack(FilingOutcome item, Caller caller) {
        try {
            blockWithAuth(documentService.moveFiles(new MoveRequest(List.of(item.documentId()), item.fromFolderId(), false)), caller);
            if (item.planId() != null) {
                planService.markUndone(item.planId(), caller);
            }
            return item.withStatus(FilingOutcome.UNDONE, "moved back to " + (item.fromPath() == null ? "/" : item.fromPath()));
        } catch (Exception e) {
            log.warn("[AUTOFILE] undo of {} failed: {}", item.documentId(), e.toString());
            return item.withStatus(FilingOutcome.FILED, "undo failed: " + reason(e));
        }
    }

    // ── stage 1 ─────────────────────────────────────────────────────────────

    /** The nearest documents by content, each with the folder it lives in now (inside the scope, writable). */
    /**
     * What the vector store says about a document: the neighbours that may vote (stage 1) and the
     * neighbours lying unfiled next to it at the scope root, which vote for nothing but tell the
     * model (stage 2) what else is waiting so a batch of one kind gets one folder.
     */
    private record NeighbourScan(List<Neighbour> votes, List<String> unfiledSiblings) {
        static final NeighbourScan EMPTY = new NeighbourScan(List.of(), List.of());
    }

    private NeighbourScan neighbours(Document document, UUID scopeRoot, String text, Caller caller) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            return NeighbourScan.EMPTY;
        }
        int topK = Math.max(3, aiProperties.getAutoFile().getNeighbourTopK());
        List<org.springframework.ai.document.Document> hits;
        try {
            hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(text.length() > QUERY_CHARS ? text.substring(0, QUERY_CHARS) : text)
                    .topK(topK * 3)   // several chunks per document; keep the best per document
                    .build());
        } catch (Exception e) {
            log.warn("[AUTOFILE] similarity search failed for {}: {}", document.getId(), e.getMessage());
            return NeighbourScan.EMPTY;
        }
        Map<UUID, Double> bestByDocument = new LinkedHashMap<>();
        for (org.springframework.ai.document.Document hit : hits) {
            Object raw = hit.getMetadata() == null ? null : hit.getMetadata().get("document_id");
            UUID id = raw == null ? null : parseUuid(raw.toString());
            if (id == null || id.equals(document.getId())) continue;
            double score = hit.getScore() == null ? 0 : hit.getScore();
            bestByDocument.merge(id, score, Math::max);
            if (bestByDocument.size() >= topK) break;
        }
        if (bestByDocument.isEmpty()) {
            return NeighbourScan.EMPTY;
        }
        List<Document> documents = blockWithAuth(documentRepository.findAllById(bestByDocument.keySet()).collectList(), caller);
        if (documents == null) {
            return NeighbourScan.EMPTY;
        }
        Map<UUID, Boolean> scopeCache = new HashMap<>();
        Set<UUID> folderIds = documents.stream()
                .filter(d -> d.getType() == DocumentType.FILE && !Boolean.FALSE.equals(d.getActive()) && d.getParentId() != null)
                .map(Document::getParentId).collect(Collectors.toSet());
        Set<UUID> writable = folderIds.isEmpty() ? Set.of()
                : accessPolicy.permitAll() ? folderIds
                : Optional.ofNullable(blockWithAuth(accessPolicy.modifiable(folderIds, caller.email()), caller)).orElse(Set.of());
        List<Neighbour> out = new ArrayList<>();
        List<String> unfiledSiblings = new ArrayList<>();
        for (Document d : documents) {
            if (d.getType() != DocumentType.FILE || Boolean.FALSE.equals(d.getActive())) continue;
            UUID folder = d.getParentId();
            // A neighbour lying at the root has no say: a file at the root is unfiled by definition.
            // Counting root neighbours let a batch dropped at the root vote to keep each other there
            // ("already in the folder where its 3 closest documents live"), which is exactly the
            // case the model stage exists for — so the root never wins stage 1. When the document
            // itself lies at the root, those neighbours are the rest of its batch: remembered for
            // the model, so it picks one folder that suits them all.
            if (folder == null) {
                if (document.getParentId() == null) unfiledSiblings.add(d.getName());
                continue;
            }
            if (!writable.contains(folder)) continue;
            if (!planService.isWithin(folder, scopeRoot, scopeCache, caller)) continue;
            out.add(new Neighbour(d.getId(), folder, bestByDocument.getOrDefault(d.getId(), 0.0)));
        }
        return new NeighbourScan(out, unfiledSiblings);
    }

    // ── stage 2 ─────────────────────────────────────────────────────────────

    /** The model's raw answer for stage 2, or null when there is no model to ask. */
    private String askModel(Document document, UUID scopeRoot, DocumentInsightView insight, String text,
                            List<String> unfiledSiblings, Caller caller, boolean allowNewFolders) {
        ResolvedChat chat = model();
        if (chat == null) {
            return null;
        }
        String inventory = planService.folderInventory(scopeRoot, caller);
        String system = systemPrompt(allowNewFolders);
        String user = userPrompt(document, scopeRoot, insight, text, inventory, unfiledSiblings, caller);
        // Same failover as the chat: a quota hit on the filing model is retried on the next
        // candidate of the chain rather than skipping the document.
        String answer = fallbackChain.callWithFailover(chat, "AUTOFILE", candidate ->
                ChatClient.builder(candidate.chatModel()).build().prompt()
                        .system(system)
                        .user(user)
                        .options(ChatOptions.builder().temperature(0.0))
                        .call()
                        .content());
        return answer;
    }

    /**
     * The scope root is deliberately not offered as a target: it is where the document already
     * lies, unfiled, and the inventory shows it holding files (its loose ones), so a model given the
     * choice picked it — "already in the target folder" — and the batch of annual reports stayed
     * put. The only answers are an existing folder, a new one, or a low confidence.
     */
    String systemPrompt(boolean allowNewFolders) {
        int maxDepth = aiProperties.getAutoFile().getNewFolderMaxDepth();
        return """
                You file documents into a document management system (%s).
                Given the folder tree of a scope and a document lying unfiled at the scope root, choose the folder it belongs in \
                and answer with ONE JSON object only:
                {"target": "<path of the destination folder relative to the scope root, e.g. Finance/Invoices/2026>", \
                "createFolders": ["<the new path when target does not exist yet>"], "confidence": <0.0-1.0>, "reason": "<one sentence>"}
                The scope root itself is never the destination: the document is already there, unfiled, and the loose files \
                listed at the root are waiting to be filed too. \
                Prefer an existing folder whose documents resemble this one (same category, client, project, period). \
                %s Never invent identifiers; be honest in the confidence.
                """.formatted(PROMPT_MARKER, allowNewFolders
                ? "When no existing folder fits, propose a new, well-named folder for this kind of document (at the scope root or "
                        + "at most " + maxDepth + " level(s) below an existing folder): documents of one kind or series — the yearly "
                        + "reports of one organisation, the invoices of one supplier — belong together in one folder, and the other "
                        + "unfiled documents listed with this one will be filed right after it, so choose a folder that suits them too. "
                        + "Answer with a low confidence only when you really cannot tell."
                : "Do not propose new folders: choose an existing one or answer with low confidence.");
    }

    String userPrompt(Document document, UUID scopeRoot, DocumentInsightView insight, String text, String inventory,
                      List<String> unfiledSiblings, Caller caller) {
        StringBuilder sb = new StringBuilder();
        sb.append("Scope: ").append(planService.pathOf(scopeRoot, caller)).append('\n');
        sb.append(inventory).append('\n');
        sb.append("Document to file: ").append(document.getName());
        if (document.getContentType() != null) sb.append(" (").append(document.getContentType()).append(')');
        sb.append('\n');
        sb.append("Current location: ").append(planService.pathOf(document.getParentId(), caller)).append(" (unfiled)\n");
        if (unfiledSiblings != null && !unfiledSiblings.isEmpty()) {
            sb.append("Other unfiled documents next to it that resemble it (to be filed after this one): ")
                    .append(String.join(", ", unfiledSiblings.size() > 10 ? unfiledSiblings.subList(0, 10) : unfiledSiblings))
                    .append(unfiledSiblings.size() > 10 ? ", ..." : "").append('\n');
        }
        if (insight != null) {
            Map<String, Object> compact = DocumentInsightStore.compact(insight);
            if (!compact.isEmpty()) sb.append("Insights: ").append(compact).append('\n');
        }
        sb.append("Text (beginning):\n").append(text.length() > MODEL_TEXT_CHARS ? text.substring(0, MODEL_TEXT_CHARS) : text);
        return sb.toString();
    }

    private ResolvedChat model() {
        ResolvedChat current = model;
        if (current != null) return current;
        try {
            ResolvedChat resolved = fallbackChain.configuredModel(aiProperties.getInsights().getModel())
                    .orElseGet(() -> resolver.resolve(null).block());
            model = resolved;
            return resolved;
        } catch (Exception e) {
            log.warn("[AUTOFILE] no model available for stage 2: {}", e.getMessage());
            return null;
        }
    }

    // ── inputs ──────────────────────────────────────────────────────────────

    /** A waiter that got no signal re-reads the row this often: the net under a row finished elsewhere. */
    static final long INSIGHT_FALLBACK_POLL_MILLIS = 5_000;

    /**
     * Wait (bounded) for the insight row to reach a terminal state; null when insights are off or late.
     *
     * <p>The wait is signal-driven: the insight worker completes {@link InsightCompletionSignal} at
     * every terminal write (DONE, FAILED, SKIPPED), so the filing wakes the moment the row lands
     * instead of polling it. The waiter registers <em>before</em> its first read, so a row completed
     * in between is not missed, and re-reads every {@link #INSIGHT_FALLBACK_POLL_MILLIS} ms in case
     * the signal never comes (a row finished by another node).
     */
    private DocumentInsightView awaitInsight(UUID documentId) {
        Duration budget = aiProperties.getAutoFile().getWaitForInsights();
        boolean wait = insightService != null && insightService.isActive() && budget != null && !budget.isZero();
        if (!wait) {
            AiDocumentInsight row = insightStore.find(documentId).block();
            return row == null ? null : DocumentInsightStore.toView(row);
        }
        long deadline = System.currentTimeMillis() + budget.toMillis();
        CompletableFuture<Void> ready = insightSignal.register(documentId);
        try {
            AiDocumentInsight row = insightStore.find(documentId).block();
            while (!settled(row)) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    ready.get(Math.min(remaining, INSIGHT_FALLBACK_POLL_MILLIS), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    // no signal within the slice: fall through to the re-read
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    // the signal completes with null, never exceptionally
                }
                if (ready.isDone()) {
                    // woken: re-arm before the read so a further write is not missed either
                    ready = insightSignal.register(documentId);
                }
                row = insightStore.find(documentId).block();
            }
            return row == null ? null : DocumentInsightStore.toView(row);
        } finally {
            insightSignal.forget(documentId, ready);
        }
    }

    /** Terminal for the filing: a tier-2 row that is DONE, or a FAILED / SKIPPED one; nothing more will come. */
    static boolean settled(AiDocumentInsight row) {
        if (row == null || AiDocumentInsight.STATUS_PENDING.equals(row.getStatus())) {
            return false;
        }
        return (row.getTier() != null && row.getTier() >= 2)
                || AiDocumentInsight.STATUS_FAILED.equals(row.getStatus())
                || AiDocumentInsight.STATUS_SKIPPED.equals(row.getStatus());
    }

    /** The text head: the index when full-text is on, else a Tika pass on the file. */
    private String textHead(Document document) {
        int max = Math.max(QUERY_CHARS, aiProperties.getInsights().getMaxChars());
        IndexService indexService = indexServiceProvider.getIfAvailable();
        if (indexService != null) {
            try {
                String text = indexService.getContent(document.getId()).block();
                if (text != null && !text.isBlank()) {
                    return text.length() > max ? text.substring(0, max) : text;
                }
            } catch (Exception e) {
                log.debug("[AUTOFILE] index lookup failed for {}: {}", document.getId(), e.getMessage());
            }
        }
        try {
            Path tempFile = Files.createTempFile("autofile-", ".tmp");
            try {
                StringBuilder collected = tikaService.processResource(tempFile, storageService.loadFile(document.getStoragePath()))
                        .reduce(new StringBuilder(), (sb, chunk) -> sb.length() < max ? sb.append(chunk) : sb)
                        .onErrorResume(e -> Mono.just(new StringBuilder()))
                        .block();
                String text = collected == null ? "" : collected.toString();
                return text.length() > max ? text.substring(0, max) : text;
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            return "";
        }
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private void submit(Task task) {
        Sinks.EmitResult result = queue.tryEmitNext(task);
        if (result == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
            queue.emitNext(task, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(2)));
        } else if (result.isFailure()) {
            log.warn("[AUTOFILE] could not queue {}: {}", task.documentId(), result);
            record(task, new FilingOutcome(task.documentId(), null, FilingOutcome.FAILED, null, null, null, null,
                    FilingOutcome.STAGE_NONE, null, "could not be queued: " + result, null, OffsetDateTime.now()));
        }
    }

    private static FilingOutcome outcome(UUID documentId, String name, String status, UUID from, String fromPath,
                                         String stage, Double confidence, String reason, UUID planId) {
        return new FilingOutcome(documentId, name, status, from, fromPath, null, null, stage, confidence, reason, planId,
                OffsetDateTime.now());
    }

    private String emailOf(Authentication authentication) {
        return authentication == null ? null : getUserAttribute(authentication, org.openfilz.dms.security.JwtTokenParser.EMAIL);
    }

    private static String createdBy(Caller caller) {
        return caller.email() == null || caller.email().isBlank() ? UserInfoService.ANONYMOUS_USER : caller.email();
    }

    private static <T> T blockWithAuth(Mono<T> mono, Caller caller) {
        return (caller.authentication() != null
                ? mono.contextWrite(ReactiveSecurityContextHolder.withAuthentication(caller.authentication()))
                : mono).block();
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static String reason(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** The first 300 characters of a model answer, on one line, for the log. */
    private static String head(String answer) {
        if (answer == null) return "null";
        String flat = answer.replace('\n', ' ').replace('\r', ' ');
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
    }
}
