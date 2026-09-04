package org.openfilz.dms.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What OpenFilz derived from a document's content, read-only for the user: tier 1 is the file's
 * own metadata (Tika), tier 2 the AI-derived category, summary, keywords and entities.
 */
public record DocumentInsightView(
        UUID documentId,
        String fileTitle,
        String fileAuthor,
        OffsetDateTime fileCreatedAt,
        OffsetDateTime fileModifiedAt,
        Integer pageCount,
        String language,
        String category,
        String summary,
        List<String> keywords,
        Map<String, Object> entities,
        int tier,
        String model,
        Integer promptVersion,
        String status,
        String error,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
