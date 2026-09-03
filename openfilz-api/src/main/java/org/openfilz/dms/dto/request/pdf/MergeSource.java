package org.openfilz.dms.dto.request.pdf;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * One input of a merge.
 *
 * @param documentId the PDF document
 * @param pages      optional page selection, e.g. {@code "1-3,7,10-"}; also {@code all}, {@code odd}, {@code even}.
 *                   Null = every page.
 */
@Schema(description = "One source document of a merge")
public record MergeSource(
        @NotNull @Schema(description = "Source PDF document id") UUID documentId,
        @Schema(description = "Page selection, e.g. \"1-3,7,10-\" (also all/odd/even); null = every page") String pages) {
}
