package org.openfilz.dms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.UUID;

public record MultipleUploadFileParameterAttributes(
        @Schema(description = "UUID of the target folder to upload the file") UUID parentFolderId,
        @Schema(description = "Metadata of the file to be uploaded") Map<String, Object> metadata) {
}
