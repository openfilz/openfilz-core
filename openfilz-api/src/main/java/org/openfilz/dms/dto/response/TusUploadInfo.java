package org.openfilz.dms.dto.response;

import java.time.OffsetDateTime;

/**
 * Response DTO containing TUS upload information.
 */
public record TusUploadInfo(
        String uploadId,
        Long offset,
        Long length,
        OffsetDateTime expiresAt,
        String uploadUrl
) {
    /**
     * Creates a TusUploadInfo with calculated upload URL.
     */
    public static TusUploadInfo of(String uploadId, Long offset, Long length, OffsetDateTime expiresAt, String baseUrl) {
        String uploadUrl = baseUrl + "/" + uploadId;
        return new TusUploadInfo(uploadId, offset, length, expiresAt, uploadUrl);
    }
}
