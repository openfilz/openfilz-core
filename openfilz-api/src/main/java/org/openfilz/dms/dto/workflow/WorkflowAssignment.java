package org.openfilz.dms.dto.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.openfilz.dms.enums.WorkflowAssigneeType;

import java.util.List;

/**
 * Who has to act on a status. {@code emails} for USERS, {@code role} for ROLE, {@code label}
 * for CHOSEN_AT_START (the question shown to the person starting the workflow).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowAssignment(WorkflowAssigneeType type, List<String> emails, String role, String label) {

    public WorkflowAssignment {
        emails = emails == null ? List.of() : emails.stream()
                .filter(e -> e != null && !e.isBlank())
                .map(e -> e.trim().toLowerCase())
                .distinct()
                .toList();
    }

    public static WorkflowAssignment initiator() {
        return new WorkflowAssignment(WorkflowAssigneeType.INITIATOR, null, null, null);
    }
}
