package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.ThumbnailService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Post-processor that triggers thumbnail generation after document upload.
 * Called asynchronously after the upload transaction completes.
 * <p>
 * Follows the same pattern as MetadataPostProcessor for full-text indexing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.thumbnail.active", havingValue = "true")
public class ThumbnailPostProcessor {

    private final ThumbnailService thumbnailService;

    /**
     * Called after document upload or content replacement.
     * Triggers async thumbnail generation.
     * <p>
     * This method is fire-and-forget - it subscribes to the thumbnail generation
     * Mono but doesn't block or propagate errors to the caller.
     *
     * @param document the uploaded/replaced document
     */
    public void processDocument(Document document) {
        if (document == null) {
            return;
        }

        if (!thumbnailService.isSupported(document.getContentType())) {
            log.debug("Thumbnail generation not supported for document: {} (type: {})",
                document.getId(), document.getContentType());
            return;
        }

        log.debug("Triggering thumbnail generation for document: {}", document.getId());

        // Fire-and-forget async generation
        thumbnailService.generateThumbnail(document)
            .doOnSuccess(v -> log.info("Thumbnail generation completed for document: {}", document.getId()))
            .doOnError(e -> log.error("Thumbnail generation failed for document: {}", document.getId(), e))
            .subscribe();
    }

    /**
     * Called when a document is deleted.
     * Deletes the associated thumbnail.
     *
     * @param documentId the deleted document ID
     */
    public void deleteDocument(UUID documentId) {
        if (documentId == null) {
            return;
        }

        log.debug("Deleting thumbnail for document: {}", documentId);

        thumbnailService.deleteThumbnail(documentId)
            .doOnSuccess(v -> log.debug("Thumbnail deleted for document: {}", documentId))
            .doOnError(e -> log.warn("Failed to delete thumbnail for document: {}", documentId, e))
            .subscribe();
    }

    /**
     * Called when a document is copied.
     * Copies the thumbnail from source to target document.
     *
     * @param sourceId source document ID
     * @param targetId target document ID
     */
    public void copyDocument(UUID sourceId, UUID targetId) {
        if (sourceId == null || targetId == null) {
            return;
        }

        log.debug("Copying thumbnail from {} to {}", sourceId, targetId);

        thumbnailService.copyThumbnail(sourceId, targetId)
            .doOnSuccess(v -> log.debug("Thumbnail copied from {} to {}", sourceId, targetId))
            .doOnError(e -> log.warn("Failed to copy thumbnail from {} to {}", sourceId, targetId, e))
            .subscribe();
    }
}
