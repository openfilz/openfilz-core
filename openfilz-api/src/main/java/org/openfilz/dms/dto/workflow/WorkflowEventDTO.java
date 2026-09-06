package org.openfilz.dms.dto.workflow;

import org.openfilz.dms.enums.WorkflowEventType;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record WorkflowEventDTO(UUID id,
                               WorkflowEventType type,
                               String fromState,
                               String toState,
                               String transitionKey,
                               String actor,
                               String comment,
                               Map<String, Object> details,
                               OffsetDateTime createdAt) {
}
