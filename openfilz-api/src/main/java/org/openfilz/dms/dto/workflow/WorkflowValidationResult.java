package org.openfilz.dms.dto.workflow;

import java.util.List;

/** Answer of {@code POST /workflows/definitions/validate}. */
public record WorkflowValidationResult(boolean valid, List<WorkflowProblem> problems) {
    public static WorkflowValidationResult of(List<WorkflowProblem> problems) {
        return new WorkflowValidationResult(problems.isEmpty(), problems);
    }
}
