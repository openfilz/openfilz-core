package org.openfilz.dms.dto.response.pdf;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One bookmark (outline entry) of a PDF, in document order.
 *
 * @param title the bookmark title
 * @param page  1-based page the bookmark points to; null when the destination could not be resolved
 * @param level nesting depth, 1 = top level
 */
@Schema(description = "One bookmark of a PDF")
public record PdfOutlineEntry(String title, Integer page, int level) {
}
