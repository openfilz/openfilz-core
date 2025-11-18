package org.openfilz.dms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.openfilz.dms.enums.DocumentType;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ListFolderRequest(
    UUID id,
    DocumentType type,
    String contentType,
    String name,
    String nameLike,
    Map<String, Object> metadata,
    Long size,
    OffsetDateTime createdAtAfter,
    OffsetDateTime createdAtBefore,
    OffsetDateTime updatedAtAfter,
    OffsetDateTime updatedAtBefore,
    String createdBy,
    String updatedBy,
    Boolean favorite,
    Boolean active,
    @NotNull @Valid PageCriteria pageInfo
    ) {

}
