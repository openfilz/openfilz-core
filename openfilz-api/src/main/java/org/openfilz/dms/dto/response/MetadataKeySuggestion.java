package org.openfilz.dms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A metadata key found on the documents visible to the caller")
public record MetadataKeySuggestion(
        @Schema(description = "The metadata key")
        String key,
        @Schema(description = "Number of visible documents carrying the key")
        long count
) {
}
