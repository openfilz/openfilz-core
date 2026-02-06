package org.openfilz.dms.service;

import org.openfilz.dms.dto.request.TusFinalizeRequest;
import org.openfilz.dms.dto.response.TusUploadInfo;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Service for handling TUS (resumable upload) protocol operations.
 * TUS is an open protocol for resumable file uploads.
 * See: https://tus.io/protocols/resumable-upload.html
 *
 * This implementation is fully reactive and works with WebFlux/Netty.
 */
public interface TusUploadService {

    /**
     * Validate all preconditions before starting an upload.
     * Checks: file size quota, user storage quota, parent folder exists, duplicate filename.
     *
     * @param uploadLength the total file size in bytes
     * @param filename the target filename (required for duplicate check)
     * @param parentFolderId the target parent folder ID (null for root)
     * @param allowDuplicateFileNames whether to allow duplicate filenames
     * @return empty Mono on success, error Mono if validation fails
     */
    Mono<Void> validateUploadCreation(Long uploadLength, String filename, UUID parentFolderId, Boolean allowDuplicateFileNames);

    /**
     * Create a new TUS upload session.
     *
     * @param uploadLength the total file size in bytes
     * @param metadata optional TUS metadata (base64 encoded key-value pairs)
     * @return the upload ID for the new session
     */
    Mono<String> createUpload(Long uploadLength, String metadata);

    /**
     * Get information about an ongoing upload.
     *
     * @param uploadId the TUS upload identifier
     * @param baseUrl the base URL for constructing the upload URL
     * @return upload information including offset and expiration
     */
    Mono<TusUploadInfo> getUploadInfo(String uploadId, String baseUrl);

    /**
     * Get the current offset (bytes received) for an upload.
     *
     * @param uploadId the TUS upload identifier
     * @return the current offset, or error if upload not found
     */
    Mono<Long> getUploadOffset(String uploadId);

    /**
     * Upload a chunk of data to an existing upload.
     *
     * @param uploadId the TUS upload identifier
     * @param offset the expected offset (must match current server offset)
     * @param data the chunk data as a Flux of DataBuffers
     * @return the new offset after the chunk is written
     */
    Mono<Long> uploadChunk(String uploadId, Long offset, Flux<DataBuffer> data);

    /**
     * Finalize a completed TUS upload by creating the Document entity.
     * This moves the file from TUS temp storage to permanent storage
     * and creates the database record.
     *
     * @param uploadId the TUS upload identifier
     * @param request the finalize request containing filename, parentFolderId, metadata
     * @return the created document response
     */
    Mono<UploadResponse> finalizeUpload(String uploadId, TusFinalizeRequest request);

    /**
     * Cancel and delete an ongoing upload.
     *
     * @param uploadId the TUS upload identifier
     * @return empty mono on success
     */
    Mono<Void> cancelUpload(String uploadId);

    /**
     * Check if an upload exists and is complete.
     *
     * @param uploadId the TUS upload identifier
     * @return true if upload exists and is complete
     */
    Mono<Boolean> isUploadComplete(String uploadId);

    /**
     * Get the total expected length of an upload.
     *
     * @param uploadId the TUS upload identifier
     * @return the total length in bytes
     */
    Mono<Long> getUploadLength(String uploadId);

    /**
     * Clean up expired uploads.
     * Called periodically by the cleanup scheduler.
     *
     * @return number of uploads cleaned up
     */
    Mono<Integer> cleanupExpiredUploads();
}
