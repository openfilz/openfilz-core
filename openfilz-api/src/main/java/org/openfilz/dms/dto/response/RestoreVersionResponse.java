package org.openfilz.dms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Result of restoring a previous version of a file document")
public record RestoreVersionResponse(
        @Schema(description = "Document identifier") UUID documentId,
        @Schema(description = "Version identifier that was restored") String restoredFromVersionId,
        @Schema(description = "Creation date of the restored version") OffsetDateTime restoredFromDate,
        @Schema(description = "Identifier of the new latest version created by the restore") String newVersionId) {
}
