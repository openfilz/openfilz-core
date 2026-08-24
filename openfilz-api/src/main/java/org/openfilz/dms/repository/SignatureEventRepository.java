package org.openfilz.dms.repository;

import org.openfilz.dms.entity.SignatureEvent;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface SignatureEventRepository extends ReactiveCrudRepository<SignatureEvent, UUID> {
    Flux<SignatureEvent> findByEnvelopeIdOrderByCreatedAtAsc(UUID envelopeId);
}
