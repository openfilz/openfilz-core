package org.openfilz.dms.service.workflow.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.WorkflowProperties;
import org.openfilz.dms.dto.workflow.SaveWorkflowDefinitionRequest;
import org.openfilz.dms.dto.workflow.WorkflowDefinitionDTO;
import org.openfilz.dms.dto.workflow.WorkflowProblem;
import org.openfilz.dms.dto.workflow.WorkflowValidationResult;
import org.openfilz.dms.dto.workflow.WorkflowAction;
import org.openfilz.dms.dto.workflow.WorkflowState;
import org.openfilz.dms.enums.WorkflowActionType;
import org.openfilz.dms.entity.WorkflowDefinition;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.WorkflowInstanceStatus;
import org.openfilz.dms.exception.WorkflowValidationException;
import org.openfilz.dms.repository.WorkflowDefinitionRepository;
import org.openfilz.dms.repository.WorkflowInstanceRepository;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.workflow.WorkflowAccessPolicy;
import org.openfilz.dms.service.workflow.WorkflowDefinitionService;
import org.openfilz.dms.service.workflow.WorkflowSpecValidator;
import org.openfilz.dms.utils.WorkflowJson;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowDefinitionServiceImpl implements WorkflowDefinitionService {

    private final WorkflowDefinitionRepository repo;
    private final WorkflowInstanceRepository instances;
    private final WorkflowProperties props;
    private final AuditService auditService;
    private final WorkflowAccessPolicy accessPolicy;
    private final TransactionalOperator tx;

    @Override
    public Flux<WorkflowDefinitionDTO> list(Boolean active) {
        Flux<WorkflowDefinition> all = active == null ? repo.findAllByOrderByNameAsc() : repo.findAllByActiveOrderByNameAsc(active);
        return all.concatMap(this::toDto);
    }

    @Override
    public Mono<WorkflowDefinitionDTO> get(UUID id) {
        return find(id).flatMap(this::toDto);
    }

    @Override
    public Mono<WorkflowDefinitionDTO> create(SaveWorkflowDefinitionRequest request, String userEmail) {
        requireValid(request);
        OffsetDateTime now = OffsetDateTime.now();
        WorkflowDefinition d = WorkflowDefinition.builder()
                .id(UUID.randomUUID()).isNew(true)
                .name(request.name().trim())
                .description(blankToNull(request.description()))
                .active(request.isActive())
                .spec(WorkflowJson.toJson(request.spec()))
                .triggerFolderIds(request.triggers().isEmpty() ? null : WorkflowJson.toJson(request.triggers().stream().map(UUID::toString).toList()))
                .version(1)
                .createdBy(userEmail)
                .createdAt(now).updatedAt(now)
                .build();
        // Mono.defer so nothing downstream is even assembled while the folder check may still
        // refuse: the guard is what stops the write, not the repository's laziness.
        return requireUsableFolders(request, userEmail)
                .then(Mono.defer(() -> requireUniqueName(d.getName(), null)))
                .then(Mono.defer(() -> repo.save(d)))
                .flatMap(saved -> auditService.logAction(AuditAction.WORKFLOW_DEFINITION_CREATED, DocumentType.FILE, saved.getId())
                        .thenReturn(saved))
                .as(tx::transactional)
                .flatMap(this::toDto);
    }

    @Override
    public Mono<WorkflowDefinitionDTO> update(UUID id, SaveWorkflowDefinitionRequest request, String userEmail) {
        requireValid(request);
        return requireUsableFolders(request, userEmail)
                .then(Mono.defer(() -> find(id)))
                .flatMap(d -> requireUniqueName(request.name().trim(), id).thenReturn(d))
                .flatMap(d -> {
                    d.setName(request.name().trim());
                    d.setDescription(blankToNull(request.description()));
                    d.setActive(request.isActive());
                    d.setSpec(WorkflowJson.toJson(request.spec()));
                    d.setTriggerFolderIds(request.triggers().isEmpty() ? null
                            : WorkflowJson.toJson(request.triggers().stream().map(UUID::toString).toList()));
                    d.setVersion(d.getVersion() + 1);
                    d.setUpdatedAt(OffsetDateTime.now());
                    return repo.save(d);
                })
                .flatMap(saved -> auditService.logAction(AuditAction.WORKFLOW_DEFINITION_UPDATED, DocumentType.FILE, saved.getId())
                        .thenReturn(saved))
                .as(tx::transactional)
                .flatMap(this::toDto);
    }

    @Override
    public Mono<Void> delete(UUID id, String userEmail) {
        return find(id)
                .flatMap(d -> instances.countByDefinitionIdAndStatus(id, WorkflowInstanceStatus.RUNNING)
                        .flatMap(running -> running > 0
                                ? Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                        running + " instance(s) of this workflow are still running — cancel them first, or deactivate the workflow"))
                                : repo.delete(d)))
                .then(auditService.logAction(AuditAction.WORKFLOW_DEFINITION_DELETED, DocumentType.FILE, id))
                .as(tx::transactional);
    }

    @Override
    public WorkflowValidationResult validate(SaveWorkflowDefinitionRequest request) {
        return WorkflowValidationResult.of(problems(request));
    }

    // ─────────────────────────────────────────────────────────────────────

    private List<WorkflowProblem> problems(SaveWorkflowDefinitionRequest request) {
        return WorkflowSpecValidator.validate(request.spec(), props.getMaxStates(), request.triggers());
    }

    /**
     * Hot folders and MOVE_TO_FOLDER destinations are written into when the workflow runs, so the
     * designer must be allowed to write there — checked here rather than trusted from the folder
     * picker. Refusals come back as ordinary {@link WorkflowProblem}s, on the path of the offending
     * folder, so the designer highlights them like any other problem. Core allows every folder
     * ({@link WorkflowAccessPolicy#canUseFolder} default); the enterprise policy answers on write access.
     */
    private Mono<Void> requireUsableFolders(SaveWorkflowDefinitionRequest request, String userEmail) {
        Map<UUID, String> paths = new LinkedHashMap<>();
        List<UUID> triggers = request.triggers();
        for (int i = 0; i < triggers.size(); i++) {
            paths.putIfAbsent(triggers.get(i), "triggerFolderIds[" + i + "]");
        }
        List<WorkflowState> states = request.spec() == null ? List.of() : request.spec().states();
        for (int i = 0; i < states.size(); i++) {
            List<WorkflowAction> onEnter = states.get(i).onEnter();
            for (int j = 0; j < onEnter.size(); j++) {
                WorkflowAction a = onEnter.get(j);
                if (a != null && a.type() == WorkflowActionType.MOVE_TO_FOLDER && a.folderId() != null) {
                    paths.putIfAbsent(a.folderId(), "states[" + i + "].onEnter[" + j + "].folderId");
                }
            }
        }
        if (paths.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(paths.entrySet())
                .concatMap(e -> accessPolicy.canUseFolder(e.getKey(), userEmail)
                        .filter(ok -> !ok)
                        .map(ignored -> WorkflowProblem.of(e.getValue(), "FOLDER_NOT_WRITABLE",
                                "You cannot write into the folder '" + e.getKey() + "'", e.getKey())))
                .collectList()
                .flatMap(problems -> problems.isEmpty() ? Mono.empty()
                        : Mono.error(new WorkflowValidationException(problems)));
    }

    private void requireValid(SaveWorkflowDefinitionRequest request) {
        List<WorkflowProblem> problems = problems(request);
        if (!problems.isEmpty()) {
            throw new WorkflowValidationException(problems);
        }
    }

    private Mono<Void> requireUniqueName(String name, UUID selfId) {
        return repo.findByNameIgnoreCase(name)
                .filter(other -> selfId == null || !other.getId().equals(selfId))
                .flatMap(other -> Mono.<Void>error(new ResponseStatusException(HttpStatus.CONFLICT,
                        "A workflow named '" + name + "' already exists")));
    }

    private Mono<WorkflowDefinition> find(UUID id) {
        return repo.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow definition not found")));
    }

    private Mono<WorkflowDefinitionDTO> toDto(WorkflowDefinition d) {
        return instances.countByDefinitionIdAndStatus(d.getId(), WorkflowInstanceStatus.RUNNING)
                .defaultIfEmpty(0L)
                .map(running -> new WorkflowDefinitionDTO(d.getId(), d.getName(), d.getDescription(), d.isActive(),
                        WorkflowJson.toSpec(d.getSpec()), WorkflowJson.toUuidList(d.getTriggerFolderIds()), d.getVersion(),
                        d.getCreatedBy(), d.getCreatedAt(), d.getUpdatedAt(), running));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
