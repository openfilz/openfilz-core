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
        @Schema(description = "Metadata key-value pairs to search for. A string value containing % or * is a "
                + "case-insensitive pattern: % or * = any characters, ? = exactly one character, _ and \\ are literal, "
                + "a bare % = the key is set whatever its value (e.g. {\"project\": \"beta%\"}). Any other string is "
                + "matched exactly (case-insensitive) and also matches the boolean or number it spells (\"true\", \"42\") "
                + "and a list containing it; a boolean, number or object value is matched as sent. "
                + "At most 10 criteria, keys up to 100 characters, patterns up to 200.")
        Map<String, Object> metadataCriteria
) {
}