package org.openfilz.dms.service;

import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.ThumbnailProperties;
import org.openfilz.dms.enums.DocumentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.openfilz.dms.config.RestApiVersion.API_PREFIX;
import static org.openfilz.dms.config.RestApiVersion.ENDPOINT_THUMBNAILS;

/**
 * Resolves thumbnail URLs for documents.
 * This component centralizes the thumbnail URL resolution logic to be reused
 * across different GraphQL controllers.
 */
@Component
public class ThumbnailUrlResolver {

    private final CommonProperties commonProperties;

    @Autowired(required = false)
    private ThumbnailProperties thumbnailProperties;

    @Autowired(required = false)
    private ThumbnailStorageService thumbnailStorageService;

    public ThumbnailUrlResolver(CommonProperties commonProperties) {
        this.commonProperties = commonProperties;
    }

    /**
     * Resolves the thumbnail URL for a document.
     * Returns the thumbnail URL if:
     * - Thumbnail feature is active
     * - Document is a FILE (not FOLDER)
     * - Content type is supported for thumbnail generation
     * - Thumbnail exists in storage
     *
     * @param id          the document ID
     * @param type        the document type (FILE or FOLDER)
     * @param contentType the content type of the document
     * @return a Mono containing the thumbnail URL, or empty if not available
     */
    public Mono<String> resolveThumbnailUrl(UUID id, DocumentType type, String contentType) {
        // Feature not active
        if (thumbnailProperties == null || thumbnailStorageService == null) {
            return Mono.empty();
        }

        // Only files can have thumbnails
        if (type == DocumentType.FOLDER) {
            return Mono.empty();
        }

        // Check if content type is supported
        if (!thumbnailProperties.isContentTypeSupported(contentType)) {
            return Mono.empty();
        }

        // Check if thumbnail exists and return URL
        return thumbnailStorageService.thumbnailExists(id)
                .flatMap(exists -> {
                    if (exists) {
                        String url = commonProperties.getApiPublicBaseUrl() + API_PREFIX + ENDPOINT_THUMBNAILS + "/img/" + id;
                        return Mono.just(url);
                    }
                    return Mono.empty();
                });
    }
}
