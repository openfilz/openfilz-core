package org.openfilz.dms.dto.response.pdf;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Result of a PDF operation.
 *
 * @param operation {@code merge}, {@code split}, {@code organize} or {@code rotate}
 * @param outputs   the documents produced or replaced, in order (several for split and batch rotate)
 */
@Schema(description = "Result of a PDF operation")
public record PdfOperationResponse(String operation, List<PdfOutputInfo> outputs) {
}
