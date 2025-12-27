package org.openfilz.dms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.openfilz.dms.enums.DocumentType;

import java.time.OffsetDateTime;
import java.util.Map;

public record FavoriteRequest(
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
    @NotNull @Valid PageCriteria pageInfo
    ) {
    public ListFolderRequest toListFolderRequest() {
        return new ListFolderRequest(null, type, contentType, name, nameLike, metadata, size, createdAtAfter,
                createdAtBefore, updatedAtAfter, updatedAtBefore, createdBy, updatedBy, true, true, pageInfo);
    }
}
