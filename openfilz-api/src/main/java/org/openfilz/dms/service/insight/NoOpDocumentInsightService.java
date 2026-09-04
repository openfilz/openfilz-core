package org.openfilz.dms.service.insight;

import org.openfilz.dms.dto.response.InsightBackfillStatus;
import org.openfilz.dms.entity.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

/** Selected when {@code openfilz.ai.insights.active} (or the AI feature) is off: nothing is enriched. */
@Service
@Lazy
@Qualifier("noOpDocumentInsightService")
public class NoOpDocumentInsightService implements DocumentInsightService {

    @Override
    public boolean isActive() {
        return false;
    }

    @Override
    public void enqueue(Document document, String textHead) {
        // inactive
    }

    @Override
    public Mono<InsightBackfillStatus> backfill(UUID folderId, boolean force) {
        return Mono.error(new IllegalStateException("Document insights are not active on this deployment"));
    }

    @Override
    public Optional<InsightBackfillStatus> backfillStatus(UUID jobId) {
        return Optional.empty();
    }
}
