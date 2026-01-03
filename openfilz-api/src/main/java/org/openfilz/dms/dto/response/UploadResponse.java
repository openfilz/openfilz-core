package org.openfilz.dms.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.UUID;

public record UploadResponse(UUID id, String name, String contentType, Long size, String errorType, String errorMessage) {

    /**
     * Creates a successful upload response.
     */
    public UploadResponse(UUID id, String name, String contentType, Long size) {
        this(id, name, contentType, size, null, null);
    }

    /**
     * Creates an error upload response from an exception.
     */
    public static UploadResponse fromError(String filename, Throwable error) {
        if (error instanceof org.openfilz.dms.exception.AbstractOpenFilzException openFilzException) {
            return new UploadResponse(null, filename, null, null, openFilzException.getError(), openFilzException.getMessage());
        }
        return new UploadResponse(null, filename, null, null, "Unknown", "An unexpected error occurred");
    }

    /**
     * Returns true if this response represents an error.
     * This method is excluded from JSON serialization.
     */
    @JsonIgnore
    public boolean isError() {
        return errorType != null;
    }
}
