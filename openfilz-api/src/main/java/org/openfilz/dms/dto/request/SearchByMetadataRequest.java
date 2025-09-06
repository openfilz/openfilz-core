package org.openfilz.dms.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.openfilz.dms.enums.DocumentType;

import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Documents matching all provided criteria will be returned")
public record SearchByMetadataRequest(
        @Schema(description = "Optional : Name of the file - if not provided or null : search all file names")
        String name,
        @Schema(description = "Optional : Type of the document to search - if not provided or null : search all types")
        DocumentType type,
        @Schema(description = "Optional : UUID of the parent folder to search - if not provided or null : search in all folders")
        UUID parentFolderId,
        @Schema(description = "Optional : if true : search only at the root level - if not provided or null : search in all folders")
        Boolean rootOnly,
        @Schema(description = "Metadata key-value pairs to search for")
        Map<String, Object> metadataCriteria
) {
}