package org.openfilz.dms.event;

import java.util.UUID;

/**
 * Published (Spring application event) when a person sets the kind of a document
 * ({@code PATCH /api/v1/documents/{id}/insights}), once the new kind is stored. Core publishes it
 * and nothing more; extension layers may react to it.
 *
 * @param documentId the document
 * @param category   the kind now stored (lower case, hyphens)
 * @param source     who set it, as recorded on the insight row ({@code user} for a person)
 */
public record DocumentCategoryChangedEvent(UUID documentId, String category, String source) {
}
