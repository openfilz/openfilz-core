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
        String email,
        /**
         * Where finalize moves the finished file, written down before the move: a finalize cut
         * after it (a timeout, a restart) is finished by the next one from the file already there.
         * Null until a finalize started.
         */
        String finalStoragePath
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
                email,
                null
        );
    }

    /**
     * Create a copy with updated offset.
     */
    public TusUploadMetadata withOffset(Long newOffset) {
        return new TusUploadMetadata(uploadId, length, newOffset, createdAt, expiresAt, metadata, email, finalStoragePath);
    }

    /**
     * Create a copy that says where finalize moves the file.
     */
    public TusUploadMetadata withFinalStoragePath(String storagePath) {
        return new TusUploadMetadata(uploadId, length, offset, createdAt, expiresAt, metadata, email, storagePath);
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
