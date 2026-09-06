package org.openfilz.dms.repository;

import org.openfilz.dms.entity.WorkflowTask;
import org.openfilz.dms.enums.WorkflowTaskStatus;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface WorkflowTaskRepository extends ReactiveCrudRepository<WorkflowTask, UUID> {

    Mono<WorkflowTask> findFirstByInstanceIdAndStatus(UUID instanceId, WorkflowTaskStatus status);

    Flux<WorkflowTask> findAllByInstanceIdOrderByCreatedAtAsc(UUID instanceId);

    /** Open tasks past their due date that were never reminded (the sweeper's input). */
    @Query("SELECT * FROM workflow_task WHERE status = 'OPEN' AND due_at IS NOT NULL AND due_at < :now AND reminded_at IS NULL "
            + "ORDER BY due_at LIMIT 500")
    Flux<WorkflowTask> findOverdueNotReminded(OffsetDateTime now);

    @Query("SELECT count(*) FROM workflow_task WHERE status = 'OPEN' AND due_at IS NOT NULL AND due_at < :now")
    Mono<Long> countOverdue(OffsetDateTime now);
}
