package org.openfilz.dms.dto.workflow;

import org.openfilz.dms.enums.WorkflowTaskStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A pending decision. In "My tasks" it also carries the document / instance context, the
 * transitions the caller may take and the decision comment of whoever moved the document here.
 */
public record WorkflowTaskDTO(UUID id,
                              UUID instanceId,
                              UUID definitionId,
                              String definitionName,
                              UUID documentId,
                              String documentName,
                              String stateKey,
                              String stateLabel,
                              String stateColor,
                              WorkflowTaskStatus status,
                              List<String> candidates,
                              String candidateRole,
                              String startedBy,
                              OffsetDateTime createdAt,
                              OffsetDateTime dueAt,
                              boolean overdue,
                              OffsetDateTime completedAt,
                              String completedBy,
                              String transitionKey,
                              String comment,
                              List<WorkflowTransition> transitions,
                              /** The comment left by whoever moved the document into this status (null on start). */
                              String previousComment,
                              String previousActor,
                              /** True when the caller is one of the candidates (by e-mail or role). */
                              boolean mine) {
}
