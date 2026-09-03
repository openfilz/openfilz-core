package org.openfilz.dms.dto.request.pdf;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Rotate pages of one or several PDFs — the batch convenience for scanned documents.
 *
 * @param documentIds the PDFs to rotate (at least one); each produces its own output
 * @param angle       clockwise rotation in degrees: 90, 180 or 270 (-90 is accepted for 270)
 * @param pages       page selection, e.g. {@code "1-3,7"} / {@code odd} / {@code even}; null = every page
 * @param output      destination; null = new version of each document. With several documents the
 *                    {@code name} of a NEW_DOCUMENT target is ignored (names are derived per source).
 */
@Schema(description = "Rotate pages of one or several PDFs")
public record RotateRequest(
        @NotNull @Schema(description = "PDF documents to rotate") List<UUID> documentIds,
        @NotNull @Schema(description = "Clockwise rotation: 90, 180 or 270") Integer angle,
        @Schema(description = "Page selection (e.g. \"1-3,7\", odd, even); null = every page") String pages,
        @Valid @Schema(description = "Destination; null = new version of each document") OutputTarget output) {
}
