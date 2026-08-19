package org.openfilz.dms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "Information about a single stored version of a file document")
public record DocumentVersionInfo(
        @Schema(description = "Storage version identifier (MinIO/S3 versionId)") String versionId,
        @Schema(description = "Date the version was created") OffsetDateTime lastModified,
        @Schema(description = "Size of the version in bytes") Long size,
        @Schema(description = "True if this version is the current (latest) one") boolean latest) {
}
