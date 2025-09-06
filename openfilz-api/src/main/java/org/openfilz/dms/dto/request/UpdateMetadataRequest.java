package org.openfilz.dms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

public record UpdateMetadataRequest(
        @Schema(description = "Metadata key-value pairs to update or add.")
        @NotEmpty
        java.util.Map<String, Object> metadataToUpdate
) {
}
