package org.openfilz.dms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

public record MultipleUploadFileParameter(
        @Schema(description = "name of the file to be uploaded : in a multiple upload request, each file name must be unique") String filename,
        MultipleUploadFileParameterAttributes fileAttributes) {
}
