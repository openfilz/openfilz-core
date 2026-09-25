package org.openfilz.dms.dto.response;

import java.util.UUID;

/**
 * One hit of {@code searchDocuments}. {@code category} and {@code language} are the document
 * insight facets mirrored into the search index when a tier-2 row lands; the OpenSearch path
 * returns them with the hit, the database path leaves them null (it filters on them but does
 * not join the insight row into the listing). {@code contentSnippet} is the best matching extract
 * of the content, matches wrapped in {@code <mark>} (OpenSearch path with a text query only).
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
                                 String language,
                                 String contentSnippet) {

    /** Backward-compatible constructor without the content snippet (null). */
    public DocumentSearchInfo(UUID id, String name, String extension, String contentType, Long size, UUID parentId,
                              String createdAt, String updatedAt, String createdBy, String updatedBy,
                              String category, String language) {
        this(id, name, extension, contentType, size, parentId, createdAt, updatedAt, createdBy, updatedBy, category, language, null);
    }

    /** Backward-compatible constructor without the insight facets (null). */
    public DocumentSearchInfo(UUID id, String name, String extension, String contentType, Long size, UUID parentId,
                              String createdAt, String updatedAt, String createdBy, String updatedBy) {
        this(id, name, extension, contentType, size, parentId, createdAt, updatedAt, createdBy, updatedBy, null, null, null);
    }

    /** This hit with its content extract. */
    public DocumentSearchInfo withContentSnippet(String snippet) {
        return new DocumentSearchInfo(id, name, extension, contentType, size, parentId, createdAt, updatedAt,
                createdBy, updatedBy, category, language, snippet);
    }
}
