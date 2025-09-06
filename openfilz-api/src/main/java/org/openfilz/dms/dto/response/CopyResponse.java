package org.openfilz.dms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record CopyResponse(
        @Schema(description = "Original file ID") UUID originalId,
        @Schema(description = "Copied file ID") UUID copyId) {
}
