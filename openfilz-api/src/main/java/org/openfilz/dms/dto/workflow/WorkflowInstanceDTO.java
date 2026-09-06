package org.openfilz.dms.dto.workflow;

import org.openfilz.dms.enums.WorkflowInstanceStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Listing row of the monitor. {@code currentTask} is the open task (null once completed / cancelled). */
public record WorkflowInstanceDTO(UUID id,
                                  UUID definitionId,
                                  String definitionName,
                                  int definitionVersion,
                                  UUID documentId,
                                  String documentName,
                                  WorkflowInstanceStatus status,
                                  String currentStateKey,
                                  String currentStateLabel,
                                  String currentStateColor,
                                  String startedBy,
                                  OffsetDateTime startedAt,
                                  OffsetDateTime updatedAt,
                                  OffsetDateTime completedAt,
                                  WorkflowTaskDTO currentTask) {
}
