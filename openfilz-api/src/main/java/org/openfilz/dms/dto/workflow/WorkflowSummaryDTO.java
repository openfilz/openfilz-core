package org.openfilz.dms.dto.workflow;

import java.util.List;
import java.util.UUID;

/** {@code GET /workflows/instances/summary}: the monitor header. */
public record WorkflowSummaryDTO(long running, long completed, long cancelled, long overdue, List<PerDefinition> byDefinition) {
    public record PerDefinition(UUID definitionId, String definitionName, long running, long completed, long cancelled) {}
}
