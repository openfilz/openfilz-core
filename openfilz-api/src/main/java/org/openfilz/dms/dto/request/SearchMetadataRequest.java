package org.openfilz.dms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

public record SearchMetadataRequest(
        @Schema(description = "Optional list of metadata keys to retrieve. If empty or null, all metadata are returned.")
        java.util.List<String> metadataKeys
) {
}