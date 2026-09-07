package org.openfilz.dms.repository;

import org.openfilz.dms.entity.SignatureRecipient;
import org.openfilz.dms.enums.SignatureRecipientStatus;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

public interface SignatureRecipientRepository extends ReactiveCrudRepository<SignatureRecipient, UUID> {

    Flux<SignatureRecipient> findByEnvelopeIdOrderByOrderIndexAscSortOrderAscIdAsc(UUID envelopeId);

    /** Every recipient of a page of envelopes, in one statement — see {@code SignatureServiceImpl.loadDtos}. */
    Flux<SignatureRecipient> findByEnvelopeIdInOrderByOrderIndexAscSortOrderAscIdAsc(Collection<UUID> envelopeIds);

    /** Token lookup: the only authenticator for tokenized links (revoked tokens are rejected by the service). */
    Mono<SignatureRecipient> findByTokenHash(String tokenHash);

    /** "Waiting for my signature" — matched by email so it works for internal and external users alike. */
    Flux<SignatureRecipient> findByRecipientEmailAndStatusInOrderByIdDesc(
            String recipientEmail, Collection<SignatureRecipientStatus> statuses);
}
