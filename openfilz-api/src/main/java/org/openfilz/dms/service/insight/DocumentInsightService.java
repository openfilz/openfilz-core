package org.openfilz.dms.service.insight;

import org.openfilz.dms.dto.response.InsightBackfillStatus;
import org.openfilz.dms.entity.Document;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

/**
 * Tier-2 document insights: a cheap model reads the head of a document's text and returns a
 * category (closed list), a summary, keywords, the language and a few entities, stored in
 * {@code ai_document_insights} and mirrored to the search index.
 * <p>
 * Runtime-switchable ({@code openfilz.ai.insights.active}, native-safe): the factory in
 * {@code DocumentInsightConfig} selects the real implementation or the no-op one at startup.
 */
public interface DocumentInsightService {

    /** True when enrichment is on for this deployment. */
    boolean isActive();

    /**
     * Queue the enrichment of a freshly ingested document. Never blocks the caller and never
     * throws: the text head is what the indexing pass already extracted (may be null, in which
     * case the service fetches it itself).
     */
    void enqueue(Document document, String textHead);

    /**
     * Enrich every FILE document that has no tier-2 insight yet (or an older prompt version
     * when {@code force}), optionally under one folder. Returns the job handle at once.
     */
    /** @param userEmail the caller — an extension with document ownership scopes the job to their documents */
    Mono<InsightBackfillStatus> backfill(UUID folderId, boolean force, String userEmail);

    Optional<InsightBackfillStatus> backfillStatus(UUID jobId);
}
