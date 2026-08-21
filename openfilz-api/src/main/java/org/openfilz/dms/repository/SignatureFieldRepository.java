package org.openfilz.dms.repository;

import org.openfilz.dms.entity.SignatureField;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SignatureFieldRepository extends ReactiveCrudRepository<SignatureField, UUID> {

    Flux<SignatureField> findByEnvelopeIdOrderBySortOrderAscIdAsc(UUID envelopeId);

    Flux<SignatureField> findByRecipientIdOrderBySortOrderAscIdAsc(UUID recipientId);

    Mono<Void> deleteByEnvelopeId(UUID envelopeId);
}
