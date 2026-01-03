package org.openfilz.dms.service;

import org.openfilz.dms.entity.Document;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Service interface for thumbnail generation and management.
 * Handles generating thumbnails via ImgProxy and storing them.
 */
public interface ThumbnailService {

    /**
     * Generates a thumbnail for the given document.
     * Called asynchronously after document upload.
     *
     * @param document the document to generate thumbnail for
     * @return Mono that completes when thumbnail generation is done
     */
    Mono<Void> generateThumbnail(Document document);

    /**
     * Retrieves the thumbnail bytes for a document.
     *
     * @param documentId the document ID
     * @return Mono containing the thumbnail bytes, or empty if not available
     */
    Mono<byte[]> getThumbnail(UUID documentId);

    /**
     * Deletes the thumbnail for a document.
     *
     * @param documentId the document ID
     * @return Mono that completes when delete is done
     */
    Mono<Void> deleteThumbnail(UUID documentId);

    /**
     * Copies thumbnail from source document to target document.
     *
     * @param sourceDocumentId source document ID
     * @param targetDocumentId target document ID
     * @return Mono that completes when copy is done
     */
    Mono<Void> copyThumbnail(UUID sourceDocumentId, UUID targetDocumentId);

    /**
     * Checks if thumbnail generation is supported for the given content type.
     *
     * @param contentType the MIME content type
     * @return true if thumbnails can be generated for this content type
     */
    boolean isSupported(String contentType);

    /**
     * Checks if a thumbnail exists for a document.
     *
     * @param documentId the document ID
     * @return Mono containing true if thumbnail exists
     */
    Mono<Boolean> thumbnailExists(UUID documentId);
}
