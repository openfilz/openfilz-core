package org.openfilz.dms.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import io.swagger.v3.oas.annotations.media.Schema;
import org.openfilz.dms.enums.DocumentType;

import java.time.OffsetDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FolderElementInfo(
        @Schema(description = "ID of the document") UUID id,
        @Schema(description = "Type of the document") DocumentType type,
        @Schema(description = "Content-Type of the file (MIME type)") String contentType,
        @Schema(description = "Name of the document") String name,
        @JsonRawValue
        @Schema(description = "Metadata (stored as JSONB)") String metadata,
        @Schema(description = "Size of the file in bytes") Long size,
        @Schema(description = "Creation timestamp") OffsetDateTime createdAt,
        @Schema(description = "Last update timestamp") OffsetDateTime updatedAt,
        @Schema(description = "User who created the document") String createdBy,
        @Schema(description = "User who last updated the document") String updatedBy,
        @Schema(description = "Whether this document is favorited by the current user") Boolean isFavorite) {
}
