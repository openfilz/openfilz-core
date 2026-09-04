package org.openfilz.dms.repository;

import org.openfilz.dms.entity.AiDocumentInsight;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

/** Reads of {@code ai_document_insights}; writes are upserts in {@code DocumentInsightStore}. */
public interface AiDocumentInsightRepository extends ReactiveCrudRepository<AiDocumentInsight, UUID> {

    Flux<AiDocumentInsight> findByCategory(String category);
}
