package org.openfilz.dms.service.workflow;

import org.openfilz.dms.dto.workflow.SaveWorkflowDefinitionRequest;
import org.openfilz.dms.dto.workflow.WorkflowDefinitionDTO;
import org.openfilz.dms.dto.workflow.WorkflowValidationResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** CRUD + validation of workflow definitions (the Designer's backend). */
public interface WorkflowDefinitionService {

    Flux<WorkflowDefinitionDTO> list(Boolean active);

    Mono<WorkflowDefinitionDTO> get(UUID id);

    Mono<WorkflowDefinitionDTO> create(SaveWorkflowDefinitionRequest request, String userEmail);

    Mono<WorkflowDefinitionDTO> update(UUID id, SaveWorkflowDefinitionRequest request, String userEmail);

    Mono<Void> delete(UUID id, String userEmail);

    WorkflowValidationResult validate(SaveWorkflowDefinitionRequest request);
}
