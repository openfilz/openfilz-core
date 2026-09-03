package org.openfilz.dms.dto.request.pdf;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Merge several PDFs (or page selections of them) into one document, in the given order.
 *
 * @param sources    ordered inputs (at least one)
 * @param addOutline add a bookmark (outline entry) per source, named after the source file
 * @param output     destination; null = new document next to the first source
 */
@Schema(description = "Merge PDFs into one document")
public record MergeRequest(
        @NotNull @Valid @Schema(description = "Ordered source documents") List<MergeSource> sources,
        @Schema(description = "Add a bookmark per source (default false)") Boolean addOutline,
        @Valid @Schema(description = "Destination; null = new document in the folder of the first source") OutputTarget output) {
}
