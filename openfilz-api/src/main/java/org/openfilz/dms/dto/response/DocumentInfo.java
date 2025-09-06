package org.openfilz.dms.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.openfilz.dms.enums.DocumentType;

import java.util.Map;
import java.util.UUID;
/**/
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentInfo(
        @Schema(description = "Type of the document") DocumentType type,
        @Schema(description = "Name of the document") String name,
        @Schema(description = "ID of the parent folder. If null, located at root.") UUID parentId,
        @Schema(description = "Metadata of the document - if requested") Map<String, Object> metadata,
        @Schema(description = "Size of the document - in bytes") Long size) {
}
