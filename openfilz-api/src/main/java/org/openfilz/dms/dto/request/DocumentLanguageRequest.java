package org.openfilz.dms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Language to set as the document's proofing language")
public record DocumentLanguageRequest(
        @Schema(description = "BCP 47 language and region, e.g. fr-FR", example = "fr-FR")
        String language
) {
}
