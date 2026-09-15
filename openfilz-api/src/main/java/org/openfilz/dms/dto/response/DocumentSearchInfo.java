package org.openfilz.dms.dto.response;

import java.util.UUID;

/**
 * One hit of {@code searchDocuments}. {@code category} and {@code language} are the document
 * insight facets mirrored into the search index when a tier-2 row lands; the OpenSearch path
 * returns them with the hit, the database path leaves them null (it filters on them but does
 * not join the insight row into the listing).
 */
public record DocumentSearchInfo(UUID id,
                                 String name,
                                 String extension,
                                 String contentType,
                                 Long size,
                                 UUID parentId,
                                 String createdAt,
                                 String updatedAt,
                                 String createdBy,
                                 String updatedBy,
                                 String category,
                                 String language) {

    /** Backward-compatible constructor without the insight facets (null). */
    public DocumentSearchInfo(UUID id, String name, String extension, String contentType, Long size, UUID parentId,
                              String createdAt, String updatedAt, String createdBy, String updatedBy) {
        this(id, name, extension, contentType, size, parentId, createdAt, updatedAt, createdBy, updatedBy, null, null);
    }
}
