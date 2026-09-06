package org.openfilz.dms.repository;

import org.openfilz.dms.entity.SignatureField;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

public interface SignatureFieldRepository extends ReactiveCrudRepository<SignatureField, UUID> {

    Flux<SignatureField> findByEnvelopeIdOrderBySortOrderAscIdAsc(UUID envelopeId);

    /** Every field of a page of envelopes, in one statement — see {@code SignatureServiceImpl.loadDtos}. */
    Flux<SignatureField> findByEnvelopeIdInOrderBySortOrderAscIdAsc(Collection<UUID> envelopeIds);

    Flux<SignatureField> findByRecipientIdOrderBySortOrderAscIdAsc(UUID recipientId);

    Mono<Void> deleteByEnvelopeId(UUID envelopeId);
}
