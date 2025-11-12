package org.openfilz.dms.dto.response;

import java.util.UUID;

public record DocumentSearchInfo(UUID id,
                                 String name,
                                 String extension,
                                 Long size,
                                 UUID parentId,
                                 String createdAt,
                                 String updatedAt,
                                 String createdBy,
                                 String updatedBy) {
}
