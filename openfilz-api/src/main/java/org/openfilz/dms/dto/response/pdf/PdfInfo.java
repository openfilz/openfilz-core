package org.openfilz.dms.dto.response.pdf;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * What the PDF tools know about a stored PDF before transforming it.
 *
 * @param documentId the document
 * @param name       file name
 * @param size       size in bytes
 * @param pageCount  number of pages (0 when the file is password-protected and could not be opened)
 * @param pages      per-page geometry
 * @param encrypted  true when the PDF is password-protected — the tools refuse to transform it
 * @param signed     true when the PDF carries a digital signature — any page change invalidates it,
 *                   so in-place edits need {@code acknowledgeSignatureLoss}
 * @param outline    bookmarks, in document order (empty when none)
 */
@Schema(description = "Structure of a stored PDF")
public record PdfInfo(UUID documentId, String name, long size, int pageCount, List<PdfPageInfo> pages,
                      boolean encrypted, boolean signed, List<PdfOutlineEntry> outline) {
}
