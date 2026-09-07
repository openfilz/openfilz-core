package org.openfilz.dms.service.workflow.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.WorkflowProperties;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.dto.workflow.StartWorkflowRequest;
import org.openfilz.dms.dto.workflow.WorkflowSpec;
import org.openfilz.dms.entity.WorkflowDefinition;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.repository.WorkflowDefinitionRepository;
import org.openfilz.dms.service.workflow.WorkflowRoles;
import org.openfilz.dms.service.workflow.WorkflowService;
import org.openfilz.dms.service.workflow.WorkflowTriggerService;
import org.openfilz.dms.utils.UserInfoService;
import org.openfilz.dms.utils.WorkflowJson;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Hot folders. For every successful upload, the active definitions whose {@code triggerFolderIds}
 * contain the parent folder are started as the uploader; the START status' first transition is
 * taken at once (a hot folder is meant to hand the document over, not to create a "submit" task
 * for the uploader). Never fails or slows the upload response beyond the start itself.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowTriggerServiceImpl implements WorkflowTriggerService, UserInfoService {

    private final WorkflowProperties props;
    private final WorkflowDefinitionRepository definitions;
    private final DocumentRepository documents;
    private final WorkflowService workflowService;
    private final WorkflowRoles roles;

    @Override
    public Mono<List<UploadResponse>> afterUpload(List<UploadResponse> responses) {
        if (!props.isActive() || responses == null || responses.isEmpty()) {
            return Mono.justOrEmpty(responses).defaultIfEmpty(List.of());
        }
        return getAuthenticationMono()
                .flatMap(auth -> Flux.fromIterable(responses)
                        .filter(r -> !r.isError() && r.id() != null)
                        .concatMap(r -> startTriggered(r, new WorkflowService.Actor(
                                String.valueOf(getUserAttribute(auth, "email")).toLowerCase(), roles.of(auth), auth, null)))
                        .then())
                .onErrorResume(e -> {
                    log.warn("[workflows] hot-folder trigger failed: {}", e.toString());
                    return Mono.empty();
                })
                .thenReturn(responses);
    }

    private Mono<Void> startTriggered(UploadResponse upload, WorkflowService.Actor actor) {
        return documents.findById(upload.id())
                .filter(doc -> doc.getParentId() != null)
                .flatMapMany(doc -> definitions.findActiveTriggeredByFolder(doc.getParentId().toString()))
                .concatMap(def -> {
                    WorkflowSpec spec = WorkflowJson.toSpec(def.getSpec());
                    String firstTransition = spec.start().flatMap(s -> s.transitions().stream().findFirst()).map(t -> t.key()).orElse(null);
                    return workflowService.start(new StartWorkflowRequest(def.getId(), upload.id(), null, firstTransition, null), actor)
                            .doOnSuccess(i -> log.info("[workflows] '{}' started on '{}' from a hot folder", def.getName(), upload.name()))
                            .onErrorResume(e -> {
                                log.warn("[workflows] could not auto-start '{}' on {}: {}", def.getName(), upload.id(), e.toString());
                                return Mono.empty();
                            });
                })
                .then();
    }
}
