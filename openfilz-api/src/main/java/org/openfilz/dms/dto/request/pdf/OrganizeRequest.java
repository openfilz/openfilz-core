package org.openfilz.dms.dto.request.pdf;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * The generic page composition: the result is exactly the listed pages, in that order. Covers
 * reorder, delete, duplicate, rotate per page, extract, and insert pages from other PDFs.
 *
 * @param documentId the main document (default source of every {@link PageInstruction}, and the
 *                   document replaced when the output mode is NEW_VERSION)
 * @param pages      the output pages in order (at least one)
 * @param output     destination; null = new version of the main document
 */
@Schema(description = "Compose a PDF from an explicit ordered list of pages")
public record OrganizeRequest(
        @NotNull @Schema(description = "Main document") UUID documentId,
        @NotNull @Schema(description = "Output pages, in order") List<PageInstruction> pages,
        @Valid @Schema(description = "Destination; null = new version of the main document") OutputTarget output) {
}
