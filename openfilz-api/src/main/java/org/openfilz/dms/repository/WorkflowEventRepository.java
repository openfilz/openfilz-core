package org.openfilz.dms.repository;

import org.openfilz.dms.entity.WorkflowEvent;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface WorkflowEventRepository extends ReactiveCrudRepository<WorkflowEvent, UUID> {

    Flux<WorkflowEvent> findAllByInstanceIdOrderByCreatedAtAsc(UUID instanceId);
}
