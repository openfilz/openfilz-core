package org.openfilz.dms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of a ZIP extraction. Extraction is best-effort per entry: an entry that cannot be
 * written (name clash, unsafe path, per-file quota…) is listed in {@link #skipped} and the others
 * are still extracted.
 */
public record UnzipResponse(
        @Schema(description = "Folder the archive was extracted into (null = root).") UUID targetFolderId,
        @Schema(description = "Id of the folder created from newFolderName, if any.") UUID createdFolderId,
        @Schema(description = "Number of folders created.") int foldersCreated,
        @Schema(description = "Number of files extracted.") int filesExtracted,
        @Schema(description = "Entries that were not extracted, with the reason.") List<UnzipSkippedEntry> skipped
) {

    /** Why an entry was not extracted. */
    public enum SkipReason {
        /** A document of that name already exists in the destination folder. */
        DUPLICATE_NAME,
        /** The entry path escapes the destination ({@code ..}, absolute path) or has an invalid name. */
        UNSAFE_PATH,
        /** The entry is encrypted or uses an unsupported compression method. */
        UNSUPPORTED,
        /** The entry is larger than the per-file upload quota. */
        FILE_TOO_LARGE,
        /** Its parent folder could not be created. */
        PARENT_NOT_CREATED,
        /** Any other failure while writing the entry. */
        ERROR
    }

    public record UnzipSkippedEntry(
            @Schema(description = "Path of the entry inside the archive.") String path,
            @Schema(description = "Why it was skipped.") SkipReason reason,
            @Schema(description = "Details, when available.") String message) {
    }
}
