package org.openfilz.dms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.openfilz.dms.enums.DocumentType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ListFolderRequest(
    UUID id,
    DocumentType type,
    String contentType,
    List<String> contentTypes,
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
    @NotNull @Valid PageCriteria pageInfo,
    Boolean recursive
    ) {

    /**
     * Backward-compatible constructor without the {@code contentTypes} multi content-type filter.
     * Existing callers keep working unchanged; {@code contentTypes} defaults to {@code null}.
     */
    public ListFolderRequest(UUID id, DocumentType type, String contentType, String name, String nameLike,
                             Map<String, Object> metadata, Long size,
                             OffsetDateTime createdAtAfter, OffsetDateTime createdAtBefore,
                             OffsetDateTime updatedAtAfter, OffsetDateTime updatedAtBefore,
                             String createdBy, String updatedBy, Boolean favorite, Boolean active,
                             PageCriteria pageInfo, Boolean recursive) {
        this(id, type, contentType, null, name, nameLike, metadata, size, createdAtAfter, createdAtBefore,
                updatedAtAfter, updatedAtBefore, createdBy, updatedBy, favorite, active, pageInfo, recursive);
    }

}
