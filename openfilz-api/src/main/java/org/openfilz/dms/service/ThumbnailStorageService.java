package org.openfilz.dms.service;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Service interface for storing and retrieving document thumbnails.
 * Implementations handle storage in local filesystem or MinIO/S3.
 */
public interface ThumbnailStorageService {

    /**
     * Saves thumbnail bytes for a document.
     * Filename is the document UUID with .png extension.
     *
     * @param documentId the document ID
     * @param thumbnailBytes the thumbnail image bytes (PNG format)
     * @return Mono that completes when save is done
     */
    Mono<Void> saveThumbnail(UUID documentId, byte[] thumbnailBytes);

    /**
     * Loads thumbnail bytes for a document.
     *
     * @param documentId the document ID
     * @return Mono containing the thumbnail bytes, or empty if not found
     */
    Mono<byte[]> loadThumbnail(UUID documentId);

    /**
     * Deletes thumbnail for a document.
     *
     * @param documentId the document ID
     * @return Mono that completes when delete is done
     */
    Mono<Void> deleteThumbnail(UUID documentId);

    /**
     * Copies thumbnail from source document to target document.
     *
     * @param sourceId source document ID
     * @param targetId target document ID
     * @return Mono that completes when copy is done
     */
    Mono<Void> copyThumbnail(UUID sourceId, UUID targetId);

    /**
     * Checks if a thumbnail exists for a document.
     *
     * @param documentId the document ID
     * @return Mono containing true if thumbnail exists, false otherwise
     */
    Mono<Boolean> thumbnailExists(UUID documentId);
}
