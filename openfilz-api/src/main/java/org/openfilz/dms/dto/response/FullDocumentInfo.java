package org.openfilz.dms.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.openfilz.dms.enums.DocumentType;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**/
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FullDocumentInfo(
        @Schema(description = "ID of the document") UUID id,
        @Schema(description = "Type of the document") DocumentType type,
        @Schema(description = "Content-Type of the document") String contentType,
        @Schema(description = "Name of the document") String name,
        @Schema(description = "ID of the parent folder. If null, located at root.") UUID parentId,
        @Schema(description = "Metadata of the document - if requested") Map<String, Object> metadata,
        @Schema(description = "Size of the document - in bytes") Long size,
        @Schema(description = "Creation date") OffsetDateTime createdAt,
        @Schema(description = "Last update date") OffsetDateTime updatedAt,
        @Schema(description = "Creation user") String createdBy,
        @Schema(description = "Last update user") String updatedBy) {
}
