package org.openfilz.dms.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Body of {@code POST/PUT /workflows/definitions}. */
public record SaveWorkflowDefinitionRequest(@NotBlank @Size(max = 100) String name,
                                            @Size(max = 1000) String description,
                                            Boolean active,
                                            @NotNull WorkflowSpec spec,
                                            /** Folders in which every new upload starts this workflow automatically. */
                                            List<UUID> triggerFolderIds) {

    public boolean isActive() {
        return active == null || active;
    }

    public List<UUID> triggers() {
        return triggerFolderIds == null ? List.of() : triggerFolderIds.stream().filter(Objects::nonNull).distinct().toList();
    }
}
