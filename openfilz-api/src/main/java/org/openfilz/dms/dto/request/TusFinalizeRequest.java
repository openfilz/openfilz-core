package org.openfilz.dms.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for finalizing a TUS upload and creating the Document entity.
 */
public record TusFinalizeRequest(
        @NotBlank(message = "Original filename is required")
        String filename,

        UUID parentFolderId,

        Map<String, Object> metadata,

        Boolean allowDuplicateFileNames,
        /** Smart filing: true / false, or null for the user's own switch. */
        Boolean autoFile
) {
    public TusFinalizeRequest {
        if (allowDuplicateFileNames == null) {
            allowDuplicateFileNames = false;
        }
    }

    /** Without the smart-filing choice (the user's own switch decides). */
    public TusFinalizeRequest(String filename, UUID parentFolderId, Map<String, Object> metadata, Boolean allowDuplicateFileNames) {
        this(filename, parentFolderId, metadata, allowDuplicateFileNames, null);
    }
}
