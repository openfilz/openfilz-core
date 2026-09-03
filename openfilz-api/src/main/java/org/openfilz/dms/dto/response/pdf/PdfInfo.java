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
 * @param activeSignatureEnvelope true while a non-terminal e-Sign envelope references this document —
 *                   saving a transformation as a new version is refused (ACTIVE_SIGNATURE_ENVELOPE),
 *                   so callers must target a new document
 * @param outline    bookmarks, in document order (empty when none)
 */
@Schema(description = "Structure of a stored PDF")
public record PdfInfo(UUID documentId, String name, long size, int pageCount, List<PdfPageInfo> pages,
                      boolean encrypted, boolean signed, boolean activeSignatureEnvelope,
                      List<PdfOutlineEntry> outline) {
}
