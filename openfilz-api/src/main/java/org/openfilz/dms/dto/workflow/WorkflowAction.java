package org.openfilz.dms.dto.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.openfilz.dms.enums.WorkflowActionType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Something OpenFilz does to the document when it reaches a status: {@code folderId} for
 * MOVE_TO_FOLDER, {@code entries} for SET_METADATA, {@code emails} for NOTIFY.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowAction(WorkflowActionType type, UUID folderId, Map<String, Object> entries, List<String> emails) {

    public WorkflowAction {
        entries = entries == null ? Map.of() : Map.copyOf(entries);
        emails = emails == null ? List.of() : emails.stream()
                .filter(e -> e != null && !e.isBlank())
                .map(e -> e.trim().toLowerCase())
                .distinct()
                .toList();
    }
}
