package org.openfilz.dms.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The proofing language of a document opened in OnlyOffice: what the document declares, what its
 * text looks like, and whether OpenFilz suggests setting it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Proofing language of a document: declared in the file vs. detected from its text")
public record DocumentLanguageInfo(
        @Schema(description = "Whether the language of this file type can be read and set (Word .docx)")
        boolean supported,
        @Schema(description = "Whether the caller may set the language (edit rights, not a locked document)")
        boolean editable,
        @Schema(description = "Language the document declares (BCP 47, e.g. en-US); absent when it declares none")
        String declared,
        @Schema(description = "Language detected from the text, with its usual region (e.g. fr-FR); absent when the text is too short or ambiguous")
        String detected,
        @Schema(description = "Confidence of the detection, 0.5 to 1")
        Double confidence,
        @Schema(description = "True when the document declares no language, or another one than its text is written in")
        boolean suggested
) {
    public static DocumentLanguageInfo unsupported() {
        return new DocumentLanguageInfo(false, false, null, null, null, false);
    }
}
