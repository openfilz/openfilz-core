package org.openfilz.dms.repository;

import org.openfilz.dms.entity.WorkflowInstance;
import org.openfilz.dms.enums.WorkflowInstanceStatus;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface WorkflowInstanceRepository extends ReactiveCrudRepository<WorkflowInstance, UUID> {

    Mono<WorkflowInstance> findFirstByDocumentIdAndStatus(UUID documentId, WorkflowInstanceStatus status);

    Flux<WorkflowInstance> findAllByDocumentIdOrderByStartedAtDesc(UUID documentId);

    Mono<Long> countByDefinitionIdAndStatus(UUID definitionId, WorkflowInstanceStatus status);

    Mono<Long> countByStatus(WorkflowInstanceStatus status);
}
