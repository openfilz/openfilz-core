package org.openfilz.dms.dto.request.pdf;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * One page of the result of an organize operation.
 *
 * @param documentId source of the page; null = the request's main document (set it to insert pages from another PDF)
 * @param page       1-based page number in the source
 * @param rotation   extra clockwise rotation in degrees (0, 90, 180, 270; negative values accepted); null = 0
 */
@Schema(description = "One output page: which source page, with which extra rotation")
public record PageInstruction(
        @Schema(description = "Source document; null = the main document") UUID documentId,
        @Schema(description = "1-based page number in the source") int page,
        @Schema(description = "Extra clockwise rotation: 0, 90, 180 or 270 (default 0)") Integer rotation) {

    public int rotationOrZero() {
        return rotation != null ? rotation : 0;
    }
}
