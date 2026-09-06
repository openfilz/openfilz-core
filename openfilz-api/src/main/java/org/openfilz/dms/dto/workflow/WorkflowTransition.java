package org.openfilz.dms.dto.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.openfilz.dms.enums.WorkflowTransitionStyle;

/** A button the assignees of a status see; moves the instance to {@code to}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowTransition(String key, String label, String to, WorkflowTransitionStyle style, Boolean requireComment) {

    public boolean commentRequired() {
        return Boolean.TRUE.equals(requireComment);
    }
}
