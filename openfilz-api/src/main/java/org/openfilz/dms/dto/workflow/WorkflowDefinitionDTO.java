package org.openfilz.dms.dto.workflow;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record WorkflowDefinitionDTO(UUID id,
                                    String name,
                                    String description,
                                    boolean active,
                                    WorkflowSpec spec,
                                    List<UUID> triggerFolderIds,
                                    int version,
                                    String createdBy,
                                    OffsetDateTime createdAt,
                                    OffsetDateTime updatedAt,
                                    /** Instances currently RUNNING on this definition. */
                                    long runningCount,
                                    /**
                                     * May the caller change this definition (edit, activate/deactivate, delete)?
                                     * Everyone who may design workflows sees and starts every definition; the
                                     * Enterprise Edition reserves the changes to its author and to admins, so the
                                     * designer can grey out what it cannot touch instead of failing on save.
                                     */
                                    boolean canEdit) {
}
