package org.openfilz.dms.dto.request;

import org.openfilz.dms.enums.DocumentType;

import java.time.OffsetDateTime;
import java.util.Map;

public record DocumentRequest(
    DocumentType type,
    String contentType,
    String name,
    Map<String, Object> metadata,
    Long size,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    String createdBy,
    String updatedBy
    ) {
}
