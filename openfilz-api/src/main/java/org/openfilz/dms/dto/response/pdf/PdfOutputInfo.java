package org.openfilz.dms.dto.response.pdf;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * One document produced (or replaced) by a PDF operation.
 *
 * @param documentId the created or replaced document
 * @param name       its file name
 * @param pageCount  pages in the result
 * @param size       size in bytes
 * @param versionId  storage version created by an in-place replace (only when versioning is enabled)
 */
@Schema(description = "A document produced by a PDF operation")
public record PdfOutputInfo(UUID documentId, String name, int pageCount, long size, String versionId) {
}
