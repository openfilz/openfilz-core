package org.openfilz.dms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

public record DeleteMetadataRequest(
        @Schema(description = "List of metadata keys to delete.")
        @NotEmpty
        java.util.List<String> metadataKeysToDelete
) {
}
