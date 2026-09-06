package org.openfilz.dms.service.workflow.impl;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.WorkflowProperties;
import org.openfilz.dms.dto.audit.WorkflowActionFailureAudit;
import org.openfilz.dms.dto.audit.WorkflowAudit;
import org.openfilz.dms.dto.audit.WorkflowAuditCause;
import org.openfilz.dms.dto.request.MoveRequest;
import org.openfilz.dms.dto.request.UpdateMetadataRequest;
import org.openfilz.dms.dto.workflow.CancelInstanceRequest;
import org.openfilz.dms.dto.workflow.CompleteTaskRequest;
import org.openfilz.dms.dto.workflow.MyTasksCountDTO;
import org.openfilz.dms.dto.workflow.ReassignTaskRequest;
import org.openfilz.dms.dto.workflow.StartWorkflowRequest;
import org.openfilz.dms.dto.workflow.WorkflowAction;
import org.openfilz.dms.dto.workflow.WorkflowAssignment;
import org.openfilz.dms.dto.workflow.WorkflowEventDTO;
import org.openfilz.dms.dto.workflow.WorkflowInstanceDTO;
import org.openfilz.dms.dto.workflow.WorkflowInstanceDetailDTO;
import org.openfilz.dms.dto.workflow.WorkflowInstanceScope;
import org.openfilz.dms.dto.workflow.WorkflowInstancePage;
import org.openfilz.dms.dto.workflow.WorkflowSpec;
import org.openfilz.dms.dto.workflow.WorkflowState;
import org.openfilz.dms.dto.workflow.WorkflowSummaryDTO;
import org.openfilz.dms.dto.workflow.WorkflowTaskDTO;
import org.openfilz.dms.dto.workflow.WorkflowTransition;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.entity.WorkflowDefinition;
import org.openfilz.dms.entity.WorkflowEvent;
import org.openfilz.dms.entity.WorkflowInstance;
import org.openfilz.dms.entity.WorkflowTask;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.WorkflowActionType;
import org.openfilz.dms.enums.WorkflowAssigneeType;
import org.openfilz.dms.enums.WorkflowEventType;
import org.openfilz.dms.enums.WorkflowInstanceStatus;
import org.openfilz.dms.enums.WorkflowTaskStatus;
import org.openfilz.dms.repository.WorkflowDefinitionRepository;
import org.openfilz.dms.repository.WorkflowEventRepository;
import org.openfilz.dms.repository.WorkflowInstanceRepository;
import org.openfilz.dms.repository.WorkflowTaskRepository;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.workflow.WorkflowAccessPolicy;
import org.openfilz.dms.service.workflow.WorkflowCommentBridge;
import org.openfilz.dms.service.workflow.WorkflowMailer;
import org.openfilz.dms.service.workflow.WorkflowNotifier;
import org.openfilz.dms.service.workflow.WorkflowService;
import org.openfilz.dms.service.workflow.WorkflowSpecValidator;
import org.openfilz.dms.utils.WorkflowJson;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The workflow engine (docs/workflows.md §4). Every mutation is one transaction that persists
 * the instance / task / history rows; notifications, mails and on-enter actions are queued in
 * a {@link SideEffects} bag and run only after the commit, under the actor's
 * {@link Authentication} so the audit log names the real person.
 */
@Slf4j
@Service
public class WorkflowServiceImpl implements WorkflowService {

    private final WorkflowDefinitionRepository definitions;
    private final WorkflowInstanceRepository instances;
    private final WorkflowTaskRepository tasks;
    private final WorkflowEventRepository events;
    private final DatabaseClient db;
    private final R2dbcEntityTemplate template;
    private final DocumentService documentService;
    private final AuditService auditService;
    private final TransactionalOperator tx;
    private final WorkflowAccessPolicy accessPolicy;
    private final WorkflowNotifier notifier;
    private final WorkflowMailer mailer;
    private final WorkflowCommentBridge commentBridge;
    private final WorkflowProperties props;
    private final CommonProperties commonProperties;

    public WorkflowServiceImpl(WorkflowDefinitionRepository definitions, WorkflowInstanceRepository instances,
                               WorkflowTaskRepository tasks, WorkflowEventRepository events, DatabaseClient db,
                               R2dbcEntityTemplate template, DocumentService documentService, AuditService auditService,
                               TransactionalOperator tx, WorkflowAccessPolicy accessPolicy, WorkflowNotifier notifier,
                               WorkflowMailer mailer, WorkflowCommentBridge commentBridge, WorkflowProperties props,
                               CommonProperties commonProperties) {
        this.definitions = definitions;
        this.instances = instances;
        this.tasks = tasks;
        this.events = events;
        this.db = db;
        this.template = template;
        this.documentService = documentService;
        this.auditService = auditService;
        this.tx = tx;
        this.accessPolicy = accessPolicy;
        this.notifier = notifier;
        this.mailer = mailer;
        this.commentBridge = commentBridge;
        this.props = props;
        this.commonProperties = commonProperties;
    }

    // ── start ─────────────────────────────────────────────────────────────

    @Override
    public Mono<WorkflowInstanceDTO> start(StartWorkflowRequest request, Actor actor) {
        SideEffects effects = new SideEffects();
        return definitions.findById(request.definitionId())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow definition not found")))
                .flatMap(def -> {
                    if (!def.isActive()) {
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "This workflow is deactivated"));
                    }
                    return documentService.findDocumentToDownloadById(request.documentId())
                            .flatMap(doc -> accessPolicy.canStart(doc, actor.email())
                                    .flatMap(ok -> ok ? Mono.just(doc)
                                            : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not start a workflow on this document"))))
                            .flatMap(doc -> instances.findFirstByDocumentIdAndStatus(doc.getId(), WorkflowInstanceStatus.RUNNING)
                                    .flatMap(running -> Mono.<Document>error(new ResponseStatusException(HttpStatus.CONFLICT,
                                            "This document is already in the workflow '" + running.getDefinitionName() + "'")))
                                    .switchIfEmpty(Mono.just(doc)))
                            .flatMap(doc -> startInstance(def, doc, request, actor, effects));
                })
                .as(tx::transactional)
                .flatMap(instance -> effects.run(actor.authentication()).thenReturn(instance))
                .flatMap(this::toDto);
    }

    private Mono<WorkflowInstance> startInstance(WorkflowDefinition def, Document doc, StartWorkflowRequest request,
                                                 Actor actor, SideEffects effects) {
        WorkflowSpec spec = WorkflowJson.toSpec(def.getSpec());
        WorkflowState start = spec.start()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Workflow has no START status"));
        Map<String, List<String>> assignments = normaliseAssignments(spec, request.assignments());
        WorkflowTransition first = null;
        if (request.transitionKey() != null && !request.transitionKey().isBlank()) {
            first = start.transition(request.transitionKey())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Unknown transition '" + request.transitionKey() + "' on the START status"));
            if (first.commentRequired() && isBlank(request.comment())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A comment is required for '" + first.label() + "'");
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        WorkflowInstance instance = WorkflowInstance.builder()
                .id(UUID.randomUUID()).isNew(true)
                .definitionId(def.getId()).definitionName(def.getName()).definitionVersion(def.getVersion())
                .spec(def.getSpec())
                .documentId(doc.getId()).documentName(doc.getName())
                .status(WorkflowInstanceStatus.RUNNING)
                .currentStateKey(start.key()).currentStateLabel(start.label())
                .startedBy(actor.email())
                .assignments(assignments.isEmpty() ? null : WorkflowJson.toJson(assignments))
                .locale(actor.locale())
                .startedAt(now).updatedAt(now)
                .build();
        WorkflowTransition transition = first;
        String startComment = transition == null ? request.comment() : null;
        return instances.save(instance)
                // The transient isNew flag drove the INSERT; every later save of this object must UPDATE.
                .doOnNext(saved -> instance.setNew(false))
                .flatMap(saved -> event(saved, WorkflowEventType.STARTED, null, start.key(), null, actor.email(), startComment, null))
                .then(auditService.logAction(AuditAction.WORKFLOW_STARTED, DocumentType.FILE, doc.getId(),
                        new WorkflowAudit(instance.getId(), def.getName(), null, start.key(), null, startComment)))
                // Quiet when a transition follows at once: nobody needs to hear about a Draft task that lasts a millisecond.
                .then(Mono.defer(() -> enterState(instance, spec, start, actor.email(), null, null, assignments, effects, transition != null)))
                .flatMap(task -> {
                    if (transition == null) return Mono.just(instance);
                    return applyTransition(instance, spec, task, start, transition, request.comment(), actor, assignments, effects);
                })
                .switchIfEmpty(Mono.just(instance));
    }

    /** Lower-cases, validates and keeps only the assignments the spec asks for. */
    private static Map<String, List<String>> normaliseAssignments(WorkflowSpec spec, Map<String, List<String>> given) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (WorkflowState s : spec.states()) {
            if (s.isEnd() || s.effectiveAssignees().type() != WorkflowAssigneeType.CHOSEN_AT_START) continue;
            List<String> emails = given == null ? null : given.get(s.key());
            List<String> clean = emails == null ? List.of() : emails.stream()
                    .filter(e -> e != null && !e.isBlank()).map(e -> e.trim().toLowerCase()).distinct().toList();
            if (clean.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please name who acts on '" + s.label() + "'");
            }
            for (String e : clean) {
                if (!WorkflowSpecValidator.EMAIL.matcher(e).matches()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid e-mail address '" + e + "' for '" + s.label() + "'");
                }
            }
            out.put(s.key(), clean);
        }
        return out;
    }

    // ── entering a status ───────────────────────────────────────────────

    /**
     * Moves the instance into {@code state}: END → completed; otherwise an OPEN task for the
     * resolved candidates. Queues the notifications and the on-enter actions.
     *
     * @return the open task (empty for an END status)
     */
    private Mono<WorkflowTask> enterState(WorkflowInstance instance, WorkflowSpec spec, WorkflowState state, String actorEmail,
                                          String previousComment, String previousActor, Map<String, List<String>> assignments,
                                          SideEffects effects) {
        return enterState(instance, spec, state, actorEmail, previousComment, previousActor, assignments, effects, false);
    }

    private Mono<WorkflowTask> enterState(WorkflowInstance instance, WorkflowSpec spec, WorkflowState state, String actorEmail,
                                          String previousComment, String previousActor, Map<String, List<String>> assignments,
                                          SideEffects effects, boolean quiet) {
        OffsetDateTime now = OffsetDateTime.now();
        instance.setCurrentStateKey(state.key());
        instance.setCurrentStateLabel(state.label());
        instance.setUpdatedAt(now);
        queueActions(instance, state, effects);
        if (state.isEnd()) {
            instance.setStatus(WorkflowInstanceStatus.COMPLETED);
            instance.setCompletedAt(now);
            List<String> recipients = new ArrayList<>(List.of(instance.getStartedBy().toLowerCase()));
            state.onEnter().stream().filter(a -> a.type() == WorkflowActionType.NOTIFY)
                    .forEach(a -> recipients.addAll(a.emails()));
            List<String> unique = recipients.stream().distinct().toList();
            String link = link("workflows?tab=monitor&instance=" + instance.getId());
            effects.add(() -> notifier.completed(instance, unique));
            effects.add(() -> Mono.fromRunnable(() -> unique.forEach(to -> mailer.sendCompleted(instance, to, link))));
            return instances.save(instance)
                    .then(event(instance, WorkflowEventType.COMPLETED, null, state.key(), null, actorEmail, null, null))
                    .then(auditService.logAction(AuditAction.WORKFLOW_COMPLETED, DocumentType.FILE, instance.getDocumentId(),
                            new WorkflowAudit(instance.getId(), instance.getDefinitionName(), null, state.key(), null, null)))
                    .then(Mono.empty());
        }
        Candidates candidates = resolveCandidates(state.effectiveAssignees(), instance, assignments);
        WorkflowTask task = WorkflowTask.builder()
                .id(UUID.randomUUID()).isNew(true)
                .instanceId(instance.getId())
                .stateKey(state.key()).stateLabel(state.label())
                .candidateRole(candidates.role())
                .status(WorkflowTaskStatus.OPEN)
                .dueAt(state.dueInDays() == null ? null : now.plusDays(state.dueInDays()))
                .createdAt(now)
                .build();
        if (!quiet) {
            String link = link("workflows?tab=tasks&task=" + task.getId());
            effects.add(() -> notifier.taskAssigned(instance, task, candidates.emails()));
            // No e-mail to someone about a task they just handed to themselves; the bell still tells them.
            List<String> toMail = candidates.emails().stream().filter(e -> !e.equalsIgnoreCase(actorEmail)).toList();
            effects.add(() -> Mono.fromRunnable(() -> toMail
                    .forEach(to -> mailer.sendTaskAssigned(instance, task, to, link, previousComment))));
        }
        return instances.save(instance)
                .then(tasks.save(task))
                .doOnNext(saved -> task.setNew(false))
                .flatMap(saved -> insertCandidates(saved.getId(), candidates.emails()).thenReturn(saved));
    }

    private record Candidates(List<String> emails, String role) {}

    private static Candidates resolveCandidates(WorkflowAssignment a, WorkflowInstance instance, Map<String, List<String>> assignments) {
        return switch (a.type()) {
            case INITIATOR -> new Candidates(List.of(instance.getStartedBy().toLowerCase()), null);
            case USERS -> new Candidates(a.emails(), null);
            case ROLE -> new Candidates(List.of(), a.role());
            case CHOSEN_AT_START -> new Candidates(assignments.getOrDefault(instance.getCurrentStateKey(), List.of()), null);
        };
    }

    private Mono<Void> insertCandidates(UUID taskId, List<String> emails) {
        return Flux.fromIterable(emails)
                .concatMap(e -> db.sql("INSERT INTO workflow_task_candidate (task_id, email) VALUES (:t, :e) ON CONFLICT DO NOTHING")
                        .bind("t", taskId).bind("e", e).fetch().rowsUpdated())
                .then();
    }

    private Mono<List<String>> loadCandidates(UUID taskId) {
        return db.sql("SELECT email FROM workflow_task_candidate WHERE task_id = :t ORDER BY email")
                .bind("t", taskId)
                .map((row, md) -> row.get("email", String.class))
                .all().collectList();
    }

    /**
     * On-enter actions run after the commit, each failure recorded as ACTION_FAILED and never propagated.
     * <p>
     * Each one carries a {@link WorkflowAuditCause} in its context, so the audit entry the action
     * itself writes (MOVE_FILE, UPDATE_DOCUMENT_METADATA…) says it was the workflow doing it — under
     * the name of the person who caused it, which the {@code SideEffects} Authentication supplies.
     */
    private void queueActions(WorkflowInstance instance, WorkflowState state, SideEffects effects) {
        WorkflowAuditCause cause = new WorkflowAuditCause(instance.getId(), instance.getDefinitionName(), state.label());
        for (WorkflowAction action : state.onEnter()) {
            effects.add(() -> runAction(instance, state, action)
                    .contextWrite(ctx -> ctx.put(WorkflowAuditCause.CONTEXT_KEY, cause))
                    .then(event(instance, WorkflowEventType.ACTION_APPLIED, null, state.key(), null, null, null,
                            Map.of("action", action.type().name(), "target", describe(action))))
                    .onErrorResume(e -> {
                        log.warn("[workflows] action {} on {} failed: {}", action.type(), instance.getDocumentId(), e.toString());
                        // A failed action leaves no trace of its own — the move simply did not happen —
                        // so the document's audit trail has to carry the attempt, not just the timeline.
                        return auditActionFailure(instance, state, action, e)
                                .then(event(instance, WorkflowEventType.ACTION_FAILED, null, state.key(), null, null, null,
                                        Map.of("action", action.type().name(), "target", describe(action), "error", String.valueOf(e.getMessage()))));
                    }));
        }
    }

    /** The failed attempt, on the document, under the actor the side effects run as. */
    private Mono<Void> auditActionFailure(WorkflowInstance instance, WorkflowState state, WorkflowAction action, Throwable error) {
        WorkflowActionFailureAudit details = new WorkflowActionFailureAudit(
                action.type().name(), describe(action), String.valueOf(error.getMessage()));
        details.setWorkflowInstanceId(instance.getId());
        details.setWorkflow(instance.getDefinitionName());
        details.setWorkflowState(state.label());
        return auditService.logAction(AuditAction.WORKFLOW_ACTION_FAILED, DocumentType.FILE, instance.getDocumentId(), details)
                .onErrorResume(e -> {
                    log.warn("[workflows] could not audit the failed action: {}", e.toString());
                    return Mono.empty();
                });
    }

    private Mono<Void> runAction(WorkflowInstance instance, WorkflowState state, WorkflowAction action) {
        return switch (action.type()) {
            case MOVE_TO_FOLDER -> documentService.moveFiles(new MoveRequest(List.of(instance.getDocumentId()), action.folderId(), false));
            case SET_METADATA -> documentService.updateDocumentMetadata(instance.getDocumentId(), new UpdateMetadataRequest(action.entries())).then();
            case NOTIFY -> {
                if (state.isEnd()) yield Mono.empty(); // folded into the completion notice
                String link = link("workflows?tab=monitor&instance=" + instance.getId());
                yield notifier.stateReached(instance, state.label(), action.emails())
                        .then(Mono.fromRunnable(() -> action.emails().forEach(to -> mailer.sendStateReached(instance, state.label(), to, link))));
            }
        };
    }

    private static String describe(WorkflowAction a) {
        return switch (a.type()) {
            case MOVE_TO_FOLDER -> String.valueOf(a.folderId());
            case SET_METADATA -> String.join(", ", a.entries().keySet());
            case NOTIFY -> String.join(", ", a.emails());
        };
    }

    // ── transitions ───────────────────────────────────────────────────────

    @Override
    public Mono<WorkflowInstanceDTO> complete(UUID taskId, CompleteTaskRequest request, Actor actor) {
        SideEffects effects = new SideEffects();
        return openTask(taskId)
                .flatMap(task -> runningInstance(task.getInstanceId()).map(i -> Map.entry(task, i)))
                .flatMap(e -> requireCandidate(e.getKey(), actor).thenReturn(e))
                .flatMap(e -> {
                    WorkflowTask task = e.getKey();
                    WorkflowInstance instance = e.getValue();
                    WorkflowSpec spec = WorkflowJson.toSpec(instance.getSpec());
                    WorkflowState state = spec.state(task.getStateKey())
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Status vanished from the snapshot"));
                    WorkflowTransition transition = state.transition(request.transitionKey())
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "Unknown transition '" + request.transitionKey() + "' on '" + state.label() + "'"));
                    if (transition.commentRequired() && isBlank(request.comment())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "A comment is required for '" + transition.label() + "'"));
                    }
                    Map<String, List<String>> assignments = WorkflowJson.toAssignments(instance.getAssignments());
                    return applyTransition(instance, spec, task, state, transition, request.comment(), actor, assignments, effects)
                            .flatMap(i -> isBlank(request.comment()) ? Mono.just(i)
                                    : Mono.just(i).doOnNext(x -> effects.add(() ->
                                    commentBridge.decisionCommented(i, task, transition.label(), actor.email(), request.comment().trim()))));
                })
                .as(tx::transactional)
                .flatMap(instance -> effects.run(actor.authentication()).thenReturn(instance))
                .flatMap(this::toDto);
    }

    /** Closes {@code task} with {@code transition} and enters the target status. Returns the instance. */
    private Mono<WorkflowInstance> applyTransition(WorkflowInstance instance, WorkflowSpec spec, WorkflowTask task, WorkflowState from,
                                                   WorkflowTransition transition, String comment, Actor actor,
                                                   Map<String, List<String>> assignments, SideEffects effects) {
        WorkflowState target = spec.state(transition.to())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Target status vanished from the snapshot"));
        String cleanComment = isBlank(comment) ? null : comment.trim();
        OffsetDateTime now = OffsetDateTime.now();
        // Conditional close: two candidates racing on the same task — the second one gets a 409.
        DatabaseClient.GenericExecuteSpec close = db.sql("UPDATE workflow_task SET status = 'DONE', completed_at = :now, completed_by = :by, "
                        + "transition_key = :tk, comment = :c WHERE id = :id AND status = 'OPEN'")
                .bind("now", now).bind("by", actor.email()).bind("tk", transition.key()).bind("id", task.getId());
        close = cleanComment == null ? close.bindNull("c", String.class) : close.bind("c", cleanComment);
        return close.fetch().rowsUpdated()
                .flatMap(n -> n == 0
                        ? Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "This task was just completed by someone else"))
                        : Mono.empty())
                .then(event(instance, WorkflowEventType.TRANSITIONED, from.key(), target.key(), transition.key(), actor.email(), cleanComment, null))
                .then(auditService.logAction(AuditAction.WORKFLOW_TRANSITIONED, DocumentType.FILE, instance.getDocumentId(),
                        new WorkflowAudit(instance.getId(), instance.getDefinitionName(), from.key(), target.key(), transition.key(), cleanComment)))
                .then(Mono.defer(() -> enterState(instance, spec, target, actor.email(), cleanComment, actor.email(), assignments, effects)))
                .then(Mono.just(instance));
    }

    private Mono<Void> requireCandidate(WorkflowTask task, Actor actor) {
        return isCandidate(task, actor).flatMap(ok -> ok ? Mono.empty()
                : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "This task is not assigned to you")));
    }

    private Mono<Boolean> isCandidate(WorkflowTask task, Actor actor) {
        if (task.getCandidateRole() != null && actor.roles().contains(task.getCandidateRole())) {
            return Mono.just(true);
        }
        return loadCandidates(task.getId()).map(c -> c.contains(actor.email().toLowerCase()));
    }

    // ── reassign / cancel ─────────────────────────────────────────────────

    @Override
    public Mono<WorkflowInstanceDTO> reassign(UUID taskId, ReassignTaskRequest request, Actor actor) {
        List<String> emails = request.emails().stream().filter(e -> e != null && !e.isBlank())
                .map(e -> e.trim().toLowerCase()).distinct().toList();
        if (emails.isEmpty()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name at least one e-mail address"));
        }
        for (String e : emails) {
            if (!WorkflowSpecValidator.EMAIL.matcher(e).matches()) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid e-mail address '" + e + "'"));
            }
        }
        SideEffects effects = new SideEffects();
        return openTask(taskId)
                .flatMap(task -> runningInstance(task.getInstanceId()).map(i -> Map.entry(task, i)))
                .flatMap(e -> Mono.zip(accessPolicy.canManage(e.getValue(), actor.email()), isCandidate(e.getKey(), actor))
                        .flatMap(t -> t.getT1() || t.getT2() ? Mono.just(e)
                                : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the initiator or a current assignee may reassign"))))
                .flatMap(e -> {
                    WorkflowTask task = e.getKey();
                    WorkflowInstance instance = e.getValue();
                    task.setCandidateRole(null);
                    instance.setUpdatedAt(OffsetDateTime.now());
                    String link = link("workflows?tab=tasks&task=" + task.getId());
                    effects.add(() -> notifier.taskAssigned(instance, task, emails));
                    effects.add(() -> Mono.fromRunnable(() -> emails.forEach(to -> mailer.sendTaskAssigned(instance, task, to, link, request.comment()))));
                    return db.sql("DELETE FROM workflow_task_candidate WHERE task_id = :t").bind("t", task.getId()).fetch().rowsUpdated()
                            .then(insertCandidates(task.getId(), emails))
                            .then(tasks.save(task))
                            .then(instances.save(instance))
                            .then(event(instance, WorkflowEventType.REASSIGNED, null, task.getStateKey(), null, actor.email(),
                                    isBlank(request.comment()) ? null : request.comment().trim(), Map.of("emails", emails)))
                            .then(auditService.logAction(AuditAction.WORKFLOW_TASK_REASSIGNED, DocumentType.FILE, instance.getDocumentId(),
                                    new WorkflowAudit(instance.getId(), instance.getDefinitionName(), null, task.getStateKey(), null, String.join(", ", emails))))
                            .thenReturn(instance);
                })
                .as(tx::transactional)
                .flatMap(instance -> effects.run(actor.authentication()).thenReturn(instance))
                .flatMap(this::toDto);
    }

    @Override
    public Mono<WorkflowInstanceDTO> cancel(UUID instanceId, CancelInstanceRequest request, Actor actor) {
        SideEffects effects = new SideEffects();
        String comment = request == null || isBlank(request.comment()) ? null : request.comment().trim();
        return runningInstance(instanceId)
                .flatMap(instance -> accessPolicy.canManage(instance, actor.email())
                        .flatMap(ok -> ok ? Mono.just(instance)
                                : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the initiator may cancel this workflow"))))
                .flatMap(instance -> tasks.findFirstByInstanceIdAndStatus(instanceId, WorkflowTaskStatus.OPEN)
                        .flatMap(task -> loadCandidates(task.getId()).flatMap(candidates -> {
                            task.setStatus(WorkflowTaskStatus.CANCELLED);
                            task.setCompletedAt(OffsetDateTime.now());
                            task.setCompletedBy(actor.email());
                            return tasks.save(task).thenReturn(candidates);
                        }))
                        .defaultIfEmpty(List.of())
                        .flatMap(candidates -> {
                            OffsetDateTime now = OffsetDateTime.now();
                            instance.setStatus(WorkflowInstanceStatus.CANCELLED);
                            instance.setCompletedAt(now);
                            instance.setUpdatedAt(now);
                            Set<String> recipients = new LinkedHashSet<>();
                            recipients.add(instance.getStartedBy().toLowerCase());
                            recipients.addAll(candidates);
                            recipients.remove(actor.email().toLowerCase());
                            List<String> unique = List.copyOf(recipients);
                            String link = link("workflows?tab=monitor&instance=" + instance.getId());
                            effects.add(() -> notifier.cancelled(instance, actor.email(), unique));
                            effects.add(() -> Mono.fromRunnable(() -> unique.forEach(to -> mailer.sendCancelled(instance, to, actor.email(), comment, link))));
                            return instances.save(instance)
                                    .then(event(instance, WorkflowEventType.CANCELLED, instance.getCurrentStateKey(), null, null, actor.email(), comment, null))
                                    .then(auditService.logAction(AuditAction.WORKFLOW_CANCELLED, DocumentType.FILE, instance.getDocumentId(),
                                            new WorkflowAudit(instance.getId(), instance.getDefinitionName(), instance.getCurrentStateKey(), null, null, comment)))
                                    .thenReturn(instance);
                        }))
                .as(tx::transactional)
                .flatMap(instance -> effects.run(actor.authentication()).thenReturn(instance))
                .flatMap(this::toDto);
    }

    // ── reads ─────────────────────────────────────────────────────────────

    @Override
    public Mono<WorkflowInstancePage> list(UUID documentId, UUID definitionId, WorkflowInstanceStatus status, String stateKey,
                                           boolean mine, int page, int size, Actor actor) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        return accessPolicy.visibleInstances(actor.email())
                .flatMap(scope -> listScoped(documentId, definitionId, status, stateKey, mine, safePage, safeSize, actor, scope));
    }

    private Mono<WorkflowInstancePage> listScoped(UUID documentId, UUID definitionId, WorkflowInstanceStatus status, String stateKey,
                                                  boolean mine, int safePage, int safeSize, Actor actor, WorkflowInstanceScope scope) {
        // Plain SQL rather than entity-mapped Criteria: the mapper turns the status filter back into the
        // enum, which the Postgres driver cannot encode.
        StringBuilder where = new StringBuilder(" FROM workflow_instance WHERE 1 = 1");
        Map<String, Object> binds = new LinkedHashMap<>();
        where.append(scope.sql("document_id"));
        binds.putAll(scope.binds());
        if (documentId != null) { where.append(" AND document_id = :documentId"); binds.put("documentId", documentId); }
        if (definitionId != null) { where.append(" AND definition_id = :definitionId"); binds.put("definitionId", definitionId); }
        if (status != null) { where.append(" AND status = :status"); binds.put("status", status.name()); }
        if (stateKey != null && !stateKey.isBlank()) { where.append(" AND current_state_key = :stateKey"); binds.put("stateKey", stateKey); }
        if (mine) { where.append(" AND lower(started_by) = :startedBy"); binds.put("startedBy", actor.email().toLowerCase()); }
        DatabaseClient.GenericExecuteSpec countSpec = db.sql("SELECT count(*) AS n" + where);
        DatabaseClient.GenericExecuteSpec rowSpec = db.sql("SELECT *" + where + " ORDER BY started_at DESC LIMIT :limit OFFSET :offset")
                .bind("limit", safeSize).bind("offset", (long) safePage * safeSize);
        for (Map.Entry<String, Object> b : binds.entrySet()) {
            countSpec = countSpec.bind(b.getKey(), b.getValue());
            rowSpec = rowSpec.bind(b.getKey(), b.getValue());
        }
        Mono<Long> total = countSpec.map((row, md) -> row.get("n", Long.class)).one().defaultIfEmpty(0L);
        Flux<WorkflowInstance> rows = rowSpec.map((row, md) -> template.getConverter().read(WorkflowInstance.class, row, md)).all();
        // No canView() post-filter here: `scope` already restricted both the rows and the total.
        return rows.concatMap(i -> toDto(i, actor))
                .collectList()
                .zipWith(total)
                .map(t -> new WorkflowInstancePage(t.getT1(), t.getT2(), safePage, safeSize));
    }

    @Override
    public Mono<WorkflowSummaryDTO> summary(Actor actor) {
        return accessPolicy.visibleInstances(actor.email()).flatMap(this::summaryScoped);
    }

    /** The tiles count exactly the instances {@link #list} would show — same scope, same numbers. */
    private Mono<WorkflowSummaryDTO> summaryScoped(WorkflowInstanceScope scope) {
        DatabaseClient.GenericExecuteSpec perSpec = db.sql(
                        "SELECT definition_id, definition_name, status, count(*) AS n FROM workflow_instance "
                                + "WHERE 1 = 1" + scope.sql("document_id")
                                + " GROUP BY definition_id, definition_name, status ORDER BY definition_name");
        for (Map.Entry<String, Object> b : scope.binds().entrySet()) {
            perSpec = perSpec.bind(b.getKey(), b.getValue());
        }
        Mono<Map<UUID, WorkflowSummaryDTO.PerDefinition>> per = perSpec
                .map((row, md) -> new Object[]{row.get("definition_id", UUID.class), row.get("definition_name", String.class),
                        row.get("status", String.class), row.get("n", Long.class)})
                .all()
                .collect(LinkedHashMap::new, (Map<UUID, WorkflowSummaryDTO.PerDefinition> acc, Object[] r) -> {
                    UUID id = (UUID) r[0];
                    WorkflowSummaryDTO.PerDefinition cur = acc.getOrDefault(id, new WorkflowSummaryDTO.PerDefinition(id, (String) r[1], 0, 0, 0));
                    long n = (Long) r[3];
                    WorkflowInstanceStatus st = WorkflowInstanceStatus.valueOf((String) r[2]);
                    acc.put(id, new WorkflowSummaryDTO.PerDefinition(id, cur.definitionName(),
                            cur.running() + (st == WorkflowInstanceStatus.RUNNING ? n : 0),
                            cur.completed() + (st == WorkflowInstanceStatus.COMPLETED ? n : 0),
                            cur.cancelled() + (st == WorkflowInstanceStatus.CANCELLED ? n : 0)));
                });
        return Mono.zip(per, countOverdue(scope).defaultIfEmpty(0L))
                .map(t -> {
                    List<WorkflowSummaryDTO.PerDefinition> list = new ArrayList<>(t.getT1().values());
                    long running = list.stream().mapToLong(WorkflowSummaryDTO.PerDefinition::running).sum();
                    long completed = list.stream().mapToLong(WorkflowSummaryDTO.PerDefinition::completed).sum();
                    long cancelled = list.stream().mapToLong(WorkflowSummaryDTO.PerDefinition::cancelled).sum();
                    return new WorkflowSummaryDTO(running, completed, cancelled, t.getT2(), list);
                });
    }

    /** Overdue tasks of the instances this caller may see (the tile sits next to the scoped counters). */
    private Mono<Long> countOverdue(WorkflowInstanceScope scope) {
        if (!scope.restricts()) {
            return tasks.countOverdue(OffsetDateTime.now());
        }
        DatabaseClient.GenericExecuteSpec spec = db.sql(
                        "SELECT count(*) AS n FROM workflow_task t JOIN workflow_instance i ON i.id = t.instance_id"
                                + " WHERE t.status = 'OPEN' AND t.due_at IS NOT NULL AND t.due_at < :now"
                                + scope.sql("i.document_id"))
                .bind("now", OffsetDateTime.now());
        for (Map.Entry<String, Object> b : scope.binds().entrySet()) {
            spec = spec.bind(b.getKey(), b.getValue());
        }
        return spec.map((row, md) -> row.get("n", Long.class)).one();
    }

    @Override
    public Mono<WorkflowInstanceDetailDTO> get(UUID instanceId, Actor actor) {
        return instances.findById(instanceId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow instance not found")))
                .flatMap(instance -> accessPolicy.canView(instance, actor.email())
                        .flatMap(ok -> ok ? Mono.just(instance)
                                : Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow instance not found"))))
                .flatMap(instance -> Mono.zip(
                        toDto(instance, actor),
                        events.findAllByInstanceIdOrderByCreatedAtAsc(instanceId).map(this::toDto).collectList(),
                        accessPolicy.canManage(instance, actor.email()))
                        .map(t -> new WorkflowInstanceDetailDTO(t.getT1(), WorkflowJson.toSpec(instance.getSpec()),
                                WorkflowJson.toAssignments(instance.getAssignments()), t.getT2(), t.getT3())));
    }

    @Override
    public Flux<WorkflowTaskDTO> myTasks(Actor actor) {
        return myOpenTasks(actor)
                .concatMap(task -> instances.findById(task.getInstanceId()).flatMap(i -> toDto(task, i, actor)))
                .sort(Comparator.comparing((WorkflowTaskDTO t) -> t.dueAt() == null ? OffsetDateTime.MAX : t.dueAt())
                        .thenComparing(WorkflowTaskDTO::createdAt));
    }

    @Override
    public Mono<MyTasksCountDTO> myTasksCount(Actor actor) {
        // The sidebar badge polls this every minute, per user, forever — so it counts in SQL
        // instead of reading the rows back to size a list.
        return bindCandidate(db.sql("SELECT count(*) AS n,"
                        + " count(*) FILTER (WHERE t.due_at IS NOT NULL AND t.due_at < :now) AS overdue"
                        + " FROM workflow_task t WHERE " + CANDIDATE_PREDICATE), actor)
                .bind("now", OffsetDateTime.now())
                .map((row, md) -> new MyTasksCountDTO(
                        row.get("n", Long.class) == null ? 0 : row.get("n", Long.class).intValue(),
                        row.get("overdue", Long.class) == null ? 0L : row.get("overdue", Long.class)))
                .one()
                .defaultIfEmpty(new MyTasksCountDTO(0, 0L));
    }

    /**
     * "This task is waiting for me": named by e-mail, or open to a role I hold.
     * <p>
     * One statement, and no join — an EXISTS for the e-mail side keeps a task with several
     * candidates from multiplying rows, and {@code = ANY} takes the whole role list as a single
     * array parameter. It used to be one query <em>per role</em> the caller held (ten, for a
     * standard user) on top of the e-mail one, run again on every badge poll.
     */
    private static final String CANDIDATE_PREDICATE =
            "t.status = 'OPEN' AND (EXISTS (SELECT 1 FROM workflow_task_candidate c"
                    + " WHERE c.task_id = t.id AND c.email = :email) OR t.candidate_role = ANY(:roles))";

    private DatabaseClient.GenericExecuteSpec bindCandidate(DatabaseClient.GenericExecuteSpec spec, Actor actor) {
        return spec.bind("email", actor.email().toLowerCase())
                .bind("roles", actor.roles().toArray(new String[0]));
    }

    /** Open tasks where the caller is a candidate by e-mail or by role. */
    private Flux<WorkflowTask> myOpenTasks(Actor actor) {
        return bindCandidate(db.sql("SELECT t.* FROM workflow_task t WHERE " + CANDIDATE_PREDICATE), actor)
                .map((row, md) -> template.getConverter().read(WorkflowTask.class, row, md))
                .all();
    }

    // ── sweeper ───────────────────────────────────────────────────────────

    @Override
    public Mono<Long> remindOverdue() {
        OffsetDateTime now = OffsetDateTime.now();
        return tasks.findOverdueNotReminded(now)
                .concatMap(task -> instances.findById(task.getInstanceId())
                        .filter(i -> i.getStatus() == WorkflowInstanceStatus.RUNNING)
                        .flatMap(instance -> loadCandidates(task.getId()).flatMap(candidates -> {
                            task.setRemindedAt(now);
                            String link = link("workflows?tab=tasks&task=" + task.getId());
                            return tasks.save(task)
                                    .then(event(instance, WorkflowEventType.REMINDED, null, task.getStateKey(), null, null, null,
                                            Map.of("emails", candidates)))
                                    .then(notifier.taskOverdue(instance, task, candidates))
                                    .then(Mono.fromRunnable(() -> candidates.forEach(to -> mailer.sendTaskOverdue(instance, task, to, link))))
                                    .thenReturn(1L);
                        }))
                        .onErrorResume(e -> {
                            log.warn("[workflows] reminder for task {} failed: {}", task.getId(), e.toString());
                            return Mono.just(0L);
                        }))
                .reduce(0L, Long::sum);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Mono<WorkflowTask> openTask(UUID taskId) {
        return tasks.findById(taskId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found")))
                .flatMap(t -> t.getStatus() == WorkflowTaskStatus.OPEN ? Mono.just(t)
                        : Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "This task is already " + t.getStatus().name().toLowerCase())));
    }

    private Mono<WorkflowInstance> runningInstance(UUID instanceId) {
        return instances.findById(instanceId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow instance not found")))
                .flatMap(i -> i.getStatus() == WorkflowInstanceStatus.RUNNING ? Mono.just(i)
                        : Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "This workflow is " + i.getStatus().name().toLowerCase())));
    }

    private Mono<Void> event(WorkflowInstance instance, WorkflowEventType type, String from, String to, String transitionKey,
                             String actor, String comment, Map<String, Object> details) {
        return events.save(WorkflowEvent.builder()
                .id(UUID.randomUUID()).isNew(true)
                .instanceId(instance.getId()).eventType(type)
                .fromState(from).toState(to).transitionKey(transitionKey)
                .actor(actor).comment(comment)
                .details(details == null ? null : WorkflowJson.toJson(details))
                .createdAt(OffsetDateTime.now())
                .build()).then();
    }

    private String link(String path) {
        String base = props.getWebBaseUrl() != null && !props.getWebBaseUrl().isBlank()
                ? props.getWebBaseUrl() : commonProperties.getWebPublicBaseUrl();
        if (base == null || base.isBlank()) base = "http://localhost:4200/";
        if (!base.endsWith("/")) base = base + "/";
        return base + path;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private Mono<WorkflowInstanceDTO> toDto(WorkflowInstance i) {
        return toDto(i, null);
    }

    private Mono<WorkflowInstanceDTO> toDto(WorkflowInstance i, Actor actor) {
        WorkflowSpec spec = WorkflowJson.toSpec(i.getSpec());
        String color = spec.state(i.getCurrentStateKey()).map(WorkflowState::color).orElse(null);
        Mono<WorkflowTaskDTO> current = i.getStatus() != WorkflowInstanceStatus.RUNNING ? Mono.empty()
                : tasks.findFirstByInstanceIdAndStatus(i.getId(), WorkflowTaskStatus.OPEN).flatMap(t -> toDto(t, i, actor));
        return current.map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())
                .map(task -> new WorkflowInstanceDTO(i.getId(), i.getDefinitionId(), i.getDefinitionName(), i.getDefinitionVersion(),
                        i.getDocumentId(), i.getDocumentName(), i.getStatus(), i.getCurrentStateKey(), i.getCurrentStateLabel(), color,
                        i.getStartedBy(), i.getStartedAt(), i.getUpdatedAt(), i.getCompletedAt(), task.orElse(null)));
    }

    private Mono<WorkflowTaskDTO> toDto(WorkflowTask t, WorkflowInstance i, Actor actor) {
        WorkflowSpec spec = WorkflowJson.toSpec(i.getSpec());
        WorkflowState state = spec.state(t.getStateKey()).orElse(null);
        Mono<List<String>> candidates = loadCandidates(t.getId());
        Mono<WorkflowEvent> previous = events.findAllByInstanceIdOrderByCreatedAtAsc(i.getId())
                .filter(e -> (e.getEventType() == WorkflowEventType.TRANSITIONED || e.getEventType() == WorkflowEventType.STARTED)
                        && t.getStateKey().equals(e.getToState()) && !e.getCreatedAt().isAfter(t.getCreatedAt().plusSeconds(1)))
                .last(WorkflowEvent.builder().build());
        return Mono.zip(candidates, previous).map(z -> {
            List<String> cands = z.getT1();
            WorkflowEvent prev = z.getT2();
            boolean mine = actor != null && (cands.contains(actor.email().toLowerCase())
                    || (t.getCandidateRole() != null && actor.roles().contains(t.getCandidateRole())));
            boolean overdue = t.getStatus() == WorkflowTaskStatus.OPEN && t.getDueAt() != null && t.getDueAt().isBefore(OffsetDateTime.now());
            return new WorkflowTaskDTO(t.getId(), i.getId(), i.getDefinitionId(), i.getDefinitionName(), i.getDocumentId(), i.getDocumentName(),
                    t.getStateKey(), t.getStateLabel(), state == null ? null : state.color(), t.getStatus(), cands, t.getCandidateRole(),
                    i.getStartedBy(), t.getCreatedAt(), t.getDueAt(), overdue, t.getCompletedAt(), t.getCompletedBy(), t.getTransitionKey(),
                    t.getComment(), state == null ? List.of() : state.transitions(),
                    prev.getComment(), prev.getActor(), mine);
        });
    }

    private WorkflowEventDTO toDto(WorkflowEvent e) {
        return new WorkflowEventDTO(e.getId(), e.getEventType(), e.getFromState(), e.getToState(), e.getTransitionKey(), e.getActor(),
                e.getComment(), WorkflowJson.toMap(e.getDetails()), e.getCreatedAt());
    }

    /**
     * Post-commit work (notifications, mails, on-enter actions). Each item runs under the actor's
     * authentication and failures are logged, never propagated to the caller.
     */
    static final class SideEffects {
        private final List<Supplier<Mono<Void>>> items = new ArrayList<>();

        void add(Supplier<Mono<Void>> item) {
            items.add(item);
        }

        Mono<Void> run(Authentication auth) {
            return Flux.fromIterable(items)
                    .concatMap(s -> Mono.defer(s)
                            .onErrorResume(e -> {
                                log.warn("[workflows] side effect failed: {}", e.toString());
                                return Mono.empty();
                            }))
                    .then()
                    .contextWrite(auth == null ? reactor.util.context.Context.empty() : ReactiveSecurityContextHolder.withAuthentication(auth));
        }
    }
}
