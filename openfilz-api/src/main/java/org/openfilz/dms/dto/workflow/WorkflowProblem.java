package org.openfilz.dms.dto.workflow;

import java.util.List;

/**
 * One validation problem of a definition: {@code path} points at the offending element (e.g.
 * {@code states[2].transitions[0].to}), {@code code} identifies the rule and {@code args} carries the
 * dynamic bits already inlined in the English {@code message} (a duplicate key, a bad e-mail, a limit…).
 * Clients localise a problem from {@code code} + {@code path} + {@code args} and keep {@code message} as
 * the fallback for codes they do not know yet.
 */
public record WorkflowProblem(String path, String code, String message, List<String> args) {

    public WorkflowProblem {
        args = args == null ? List.of() : List.copyOf(args);
    }

    public WorkflowProblem(String path, String code, String message) {
        this(path, code, message, List.of());
    }

    /** A problem whose message interpolates a single value the client re-renders in its own language. */
    public static WorkflowProblem of(String path, String code, String message, Object arg) {
        return new WorkflowProblem(path, code, message, List.of(String.valueOf(arg)));
    }
}
