package org.openfilz.dms.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import static org.openfilz.dms.controller.ApiDescription.ALLOW_DUPLICATE_FILE_NAME_PARAM_DESCRIPTION;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CopyRequest(
        @Schema(description = "List of document IDs (files or folders) to copy.") java.util.List<UUID> documentIds,
        @Schema(description = "ID of the target folder.") UUID targetFolderId,
        @Schema(description = ALLOW_DUPLICATE_FILE_NAME_PARAM_DESCRIPTION) Boolean allowDuplicateFileNames
) {
}