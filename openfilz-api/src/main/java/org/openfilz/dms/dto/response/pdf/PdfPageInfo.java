package org.openfilz.dms.dto.response.pdf;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One page of a PDF.
 *
 * @param number   1-based page number
 * @param width    media box width in PDF points (1/72 inch)
 * @param height   media box height in PDF points
 * @param rotation the page's own /Rotate value (0, 90, 180, 270)
 */
@Schema(description = "One page of a PDF")
public record PdfPageInfo(int number, double width, double height, int rotation) {
}
