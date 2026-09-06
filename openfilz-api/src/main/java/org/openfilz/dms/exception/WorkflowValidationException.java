package org.openfilz.dms.exception;

import lombok.Getter;
import org.openfilz.dms.dto.workflow.WorkflowProblem;

import java.util.List;

/** A workflow definition failed {@code WorkflowSpecValidator}: answered 400 with the problem list. */
@Getter
public class WorkflowValidationException extends RuntimeException {

    private final transient List<WorkflowProblem> problems;

    public WorkflowValidationException(List<WorkflowProblem> problems) {
        super("Invalid workflow definition: " + problems.stream().map(WorkflowProblem::message).findFirst().orElse("")
                + (problems.size() > 1 ? " (+" + (problems.size() - 1) + " more)" : ""));
        this.problems = List.copyOf(problems);
    }

    /** Error body: the usual {status, message} plus the problems the designer highlights. */
    public record Body(int status, String message, List<WorkflowProblem> problems) {}
}
