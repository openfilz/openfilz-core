package org.openfilz.dms.dto.request.pdf;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Split one PDF into several new documents.
 *
 * @param documentId   the PDF to split
 * @param mode         how to cut
 * @param n            EVERY_N_PAGES: pages per part
 * @param pages        AT_PAGES: pages at which a new part starts (2..pageCount)
 * @param ranges       PAGE_RANGES: one page selection per part, e.g. {@code ["1-3","4-"]}
 * @param outlineLevel BY_OUTLINE_LEVEL: deepest bookmark level that starts a part (default 1)
 * @param output       destination of the parts; null = same folder, {@code {name}-{index}}
 */
@Schema(description = "Split a PDF into several documents")
public record SplitRequest(
        @NotNull @Schema(description = "PDF document to split") UUID documentId,
        @NotNull @Schema(description = "Split mode") SplitMode mode,
        @Schema(description = "EVERY_N_PAGES: pages per part") Integer n,
        @Schema(description = "AT_PAGES: pages at which a new part starts") List<Integer> pages,
        @Schema(description = "PAGE_RANGES: one page selection per part") List<String> ranges,
        @Schema(description = "BY_OUTLINE_LEVEL: deepest bookmark level starting a part (default 1)") Integer outlineLevel,
        @Valid @Schema(description = "Destination of the parts") SplitOutput output) {
}
