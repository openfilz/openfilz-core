package org.openfilz.dms.dto.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.openfilz.dms.enums.WorkflowStateKind;

import java.util.List;
import java.util.Optional;

/** One status of a workflow: who acts, which buttons they get, what happens on entry. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowState(String key,
                            String label,
                            WorkflowStateKind kind,
                            String color,
                            WorkflowAssignment assignees,
                            Integer dueInDays,
                            List<WorkflowTransition> transitions,
                            List<WorkflowAction> onEnter) {

    public WorkflowState {
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
        onEnter = onEnter == null ? List.of() : List.copyOf(onEnter);
    }

    public boolean isEnd() {
        return kind == WorkflowStateKind.END;
    }

    public Optional<WorkflowTransition> transition(String transitionKey) {
        if (transitionKey == null) return Optional.empty();
        return transitions.stream().filter(t -> transitionKey.equals(t.key())).findFirst();
    }

    /** Absent assignees on a non-END state mean "the initiator". */
    public WorkflowAssignment effectiveAssignees() {
        return assignees != null ? assignees : WorkflowAssignment.initiator();
    }
}
