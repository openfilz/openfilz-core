package org.openfilz.dms.repository;

import org.openfilz.dms.entity.WorkflowDefinition;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface WorkflowDefinitionRepository extends ReactiveCrudRepository<WorkflowDefinition, UUID> {

    Flux<WorkflowDefinition> findAllByOrderByNameAsc();

    Flux<WorkflowDefinition> findAllByActiveOrderByNameAsc(boolean active);

    @Query("SELECT * FROM workflow_definition WHERE lower(name) = lower(:name)")
    Mono<WorkflowDefinition> findByNameIgnoreCase(String name);

    /** Active definitions whose trigger folders contain the given folder (JSONB containment). */
    @Query("SELECT * FROM workflow_definition WHERE active = TRUE AND trigger_folder_ids IS NOT NULL "
            + "AND trigger_folder_ids @> to_jsonb(ARRAY[CAST(:folderId AS text)]) ORDER BY name")
    Flux<WorkflowDefinition> findActiveTriggeredByFolder(String folderId);
}
