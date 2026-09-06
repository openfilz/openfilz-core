package org.openfilz.dms.dto.workflow;

/** One validation problem of a definition: {@code path} points at the offending element (e.g. {@code states[2].transitions[0].to}). */
public record WorkflowProblem(String path, String code, String message) {
}
