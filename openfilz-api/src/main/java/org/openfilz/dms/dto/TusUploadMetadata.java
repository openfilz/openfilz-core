package org.openfilz.dms.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.Map;

/**
 * Metadata for a TUS upload session.
 * Stored as a JSON file alongside the upload data file.
 */
public record TusUploadMetadata(
        String uploadId,
        Long length,
        Long offset,
        Instant createdAt,
        Instant expiresAt,
        Map<String, String> metadata,
        String email
) {
    /**
     * Create a new upload metadata with initial values.
     */
    public static TusUploadMetadata create(String uploadId, Long length, long expirationMs, Map<String, String> metadata,
                                           String email) {
        Instant now = Instant.now();
        return new TusUploadMetadata(
                uploadId,
                length,
                0L,
                now,
                now.plusMillis(expirationMs),
                metadata,
                email
        );
    }

    /**
     * Create a copy with updated offset.
     */
    public TusUploadMetadata withOffset(Long newOffset) {
        return new TusUploadMetadata(uploadId, length, newOffset, createdAt, expiresAt, metadata, email);
    }

    /**
     * Check if the upload is complete.
     */
    @JsonIgnore
    public boolean isComplete() {
        return offset != null && offset.equals(length);
    }

    /**
     * Check if the upload has expired.
     */
    @JsonIgnore
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
