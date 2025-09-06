package org.openfilz.dms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

public record ReplaceDocumentRequest(
        @Schema(description = "Optional new metadata for the document. Replaces existing metadata if provided.")
        java.util.Map<String, Object> metadata
        // FilePart for new content will be handled separately in the controller
) {
}