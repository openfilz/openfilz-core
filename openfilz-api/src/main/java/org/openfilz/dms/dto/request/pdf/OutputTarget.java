package org.openfilz.dms.dto.request.pdf;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Destination of a single-output PDF operation (merge, organize, rotate).
 *
 * @param mode                     {@link OutputMode#NEW_DOCUMENT} or {@link OutputMode#NEW_VERSION}. Defaults to
 *                                 NEW_VERSION for organize/rotate and NEW_DOCUMENT for merge.
 * @param folderId                 NEW_DOCUMENT only: folder of the new document; null = the folder of the
 *                                 (first) source.
 * @param name                     NEW_DOCUMENT only: file name of the new document (".pdf" is appended when
 *                                 missing); null = a name derived from the (first) source.
 * @param allowDuplicateFileNames  NEW_DOCUMENT only: accept a name that already exists in the folder (default false).
 * @param acknowledgeSignatureLoss NEW_VERSION only: required when the source carries a digital signature, which any
 *                                 page change invalidates.
 */
@Schema(description = "Destination of the result of a PDF operation")
public record OutputTarget(
        @Schema(description = "NEW_DOCUMENT (default for merge) or NEW_VERSION (default for organize/rotate)")
        OutputMode mode,
        @Schema(description = "NEW_DOCUMENT: target folder; null = folder of the (first) source")
        UUID folderId,
        @Schema(description = "NEW_DOCUMENT: file name; null = derived from the (first) source")
        String name,
        @Schema(description = "NEW_DOCUMENT: allow a name already present in the folder (default false)")
        Boolean allowDuplicateFileNames,
        @Schema(description = "NEW_VERSION: confirm that an existing digital signature may be invalidated")
        Boolean acknowledgeSignatureLoss) {

    public static OutputTarget defaults() {
        return new OutputTarget(null, null, null, null, null);
    }

    public OutputMode modeOr(OutputMode fallback) {
        return mode != null ? mode : fallback;
    }

    public boolean allowDuplicates() {
        return Boolean.TRUE.equals(allowDuplicateFileNames);
    }

    public boolean signatureLossAcknowledged() {
        return Boolean.TRUE.equals(acknowledgeSignatureLoss);
    }
}
