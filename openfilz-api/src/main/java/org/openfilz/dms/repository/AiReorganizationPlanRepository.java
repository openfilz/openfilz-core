package org.openfilz.dms.repository;

import org.openfilz.dms.entity.AiReorganizationPlan;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import java.util.UUID;

public interface AiReorganizationPlanRepository extends ReactiveCrudRepository<AiReorganizationPlan, UUID> {

    /** The latest plan of an origin for a document (smart-filing records carry the document id). */
    reactor.core.publisher.Mono<AiReorganizationPlan> findFirstByDocumentIdAndOriginOrderByCreatedAtDesc(UUID documentId, String origin);
}
