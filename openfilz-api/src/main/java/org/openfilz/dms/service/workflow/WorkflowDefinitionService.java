package org.openfilz.dms.service.workflow;

import org.openfilz.dms.dto.workflow.SaveWorkflowDefinitionRequest;
import org.openfilz.dms.dto.workflow.WorkflowDefinitionDTO;
import org.openfilz.dms.dto.workflow.WorkflowValidationResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * CRUD + validation of workflow definitions (the Designer's backend).
 * <p>
 * Every method takes the caller: the reads need it to tell the designer which definitions it may
 * change ({@code canEdit}), the writes to enforce it. See
 * {@link WorkflowAccessPolicy#canEditDefinition}.
 */
public interface WorkflowDefinitionService {

    Flux<WorkflowDefinitionDTO> list(Boolean active, WorkflowService.Actor actor);

    Mono<WorkflowDefinitionDTO> get(UUID id, WorkflowService.Actor actor);

    Mono<WorkflowDefinitionDTO> create(SaveWorkflowDefinitionRequest request, WorkflowService.Actor actor);

    Mono<WorkflowDefinitionDTO> update(UUID id, SaveWorkflowDefinitionRequest request, WorkflowService.Actor actor);

    Mono<Void> delete(UUID id, WorkflowService.Actor actor);

    WorkflowValidationResult validate(SaveWorkflowDefinitionRequest request);
}
