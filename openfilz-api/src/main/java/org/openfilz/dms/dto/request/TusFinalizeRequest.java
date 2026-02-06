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

        Boolean allowDuplicateFileNames
) {
    public TusFinalizeRequest {
        if (allowDuplicateFileNames == null) {
            allowDuplicateFileNames = false;
        }
    }
}
