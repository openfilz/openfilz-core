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
 * Every method takes the caller: a definition the caller may not see
 * ({@link WorkflowAccessPolicy#visibleDefinitions}) is left out of the listing and answers 404
 * everywhere else; the reads also tell the designer which definitions it may change
 * ({@code canEdit}), the writes enforce it ({@link WorkflowAccessPolicy#canEditDefinition}).
 */
public interface WorkflowDefinitionService {

    /** {@code active} null = both; {@code mine} = created by the caller. */
    Flux<WorkflowDefinitionDTO> list(Boolean active, boolean mine, WorkflowService.Actor actor);

    Mono<WorkflowDefinitionDTO> get(UUID id, WorkflowService.Actor actor);

    Mono<WorkflowDefinitionDTO> create(SaveWorkflowDefinitionRequest request, WorkflowService.Actor actor);

    Mono<WorkflowDefinitionDTO> update(UUID id, SaveWorkflowDefinitionRequest request, WorkflowService.Actor actor);

    Mono<Void> delete(UUID id, WorkflowService.Actor actor);

    /**
     * 404 unless the definition exists and the caller may see it. The REST start calls it before
     * {@link WorkflowService#start}; a hot-folder start does not (see
     * {@link WorkflowAccessPolicy#visibleDefinitions}).
     */
    Mono<Void> requireVisible(UUID id, WorkflowService.Actor actor);

    WorkflowValidationResult validate(SaveWorkflowDefinitionRequest request);
}
