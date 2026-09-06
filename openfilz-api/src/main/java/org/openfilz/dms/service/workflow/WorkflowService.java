package org.openfilz.dms.service.workflow;

import org.openfilz.dms.dto.workflow.CancelInstanceRequest;
import org.openfilz.dms.dto.workflow.CompleteTaskRequest;
import org.openfilz.dms.dto.workflow.MyTasksCountDTO;
import org.openfilz.dms.dto.workflow.ReassignTaskRequest;
import org.openfilz.dms.dto.workflow.StartWorkflowRequest;
import org.openfilz.dms.dto.workflow.WorkflowInstanceDTO;
import org.openfilz.dms.dto.workflow.WorkflowInstanceDetailDTO;
import org.openfilz.dms.dto.workflow.WorkflowInstancePage;
import org.openfilz.dms.dto.workflow.WorkflowSummaryDTO;
import org.openfilz.dms.dto.workflow.WorkflowTaskDTO;
import org.openfilz.dms.enums.WorkflowInstanceStatus;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/** The engine: instances, tasks, transitions, actions, history. See docs/workflows.md §4. */
public interface WorkflowService {

    /** The caller: e-mail from the JWT, role names, the Authentication (actions run under it), the preferred locale. */
    record Actor(String email, List<String> roles, Authentication authentication, String locale) {}

    Mono<WorkflowInstanceDTO> start(StartWorkflowRequest request, Actor actor);

    /** Filters may be null. {@code mine} = started by the caller. */
    Mono<WorkflowInstancePage> list(UUID documentId, UUID definitionId, WorkflowInstanceStatus status, String stateKey,
                                    boolean mine, int page, int size, Actor actor);

    Mono<WorkflowSummaryDTO> summary(Actor actor);

    Mono<WorkflowInstanceDetailDTO> get(UUID instanceId, Actor actor);

    Mono<WorkflowInstanceDTO> cancel(UUID instanceId, CancelInstanceRequest request, Actor actor);

    Flux<WorkflowTaskDTO> myTasks(Actor actor);

    Mono<MyTasksCountDTO> myTasksCount(Actor actor);

    Mono<WorkflowInstanceDTO> complete(UUID taskId, CompleteTaskRequest request, Actor actor);

    Mono<WorkflowInstanceDTO> reassign(UUID taskId, ReassignTaskRequest request, Actor actor);

    /** The sweeper: remind every overdue open task once. @return number of reminders sent. */
    Mono<Long> remindOverdue();
}
