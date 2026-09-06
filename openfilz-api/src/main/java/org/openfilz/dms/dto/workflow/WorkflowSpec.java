package org.openfilz.dms.dto.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.openfilz.dms.enums.WorkflowStateKind;

import java.util.List;
import java.util.Optional;

/**
 * The state machine of a workflow definition (stored as JSON in {@code workflow_definition.spec}
 * and snapshotted into {@code workflow_instance.spec}). See docs/workflows.md §3.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowSpec(List<WorkflowState> states) {

    public WorkflowSpec {
        states = states == null ? List.of() : List.copyOf(states);
    }

    public Optional<WorkflowState> state(String key) {
        if (key == null) return Optional.empty();
        return states.stream().filter(s -> key.equals(s.key())).findFirst();
    }

    public Optional<WorkflowState> start() {
        return states.stream().filter(s -> s.kind() == WorkflowStateKind.START).findFirst();
    }
}
