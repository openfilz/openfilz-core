package org.openfilz.dms.dto.workflow;

import java.util.List;
import java.util.Map;

/** {@code GET /workflows/instances/{id}}: the instance, its snapshot spec (for the diagram), its history and my rights on it. */
public record WorkflowInstanceDetailDTO(WorkflowInstanceDTO instance,
                                        WorkflowSpec spec,
                                        Map<String, List<String>> assignments,
                                        List<WorkflowEventDTO> history,
                                        /** True when the caller may cancel / reassign (initiator, or what the edition policy allows). */
                                        boolean canManage) {
}
