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
                                    long runningCount) {
}
