package org.openfilz.dms.service.signature;

import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureRecipient;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

/**
 * Builds the {@link Authentication} used to attribute e-Sign audit rows to the real actor
 * instead of {@code anonymousUser}. There is deliberately no "log as arbitrary string" path —
 * the principal always comes from a context the caller cannot forge: the signer identity is
 * read from the recipient row resolved by a validated signing token; the requester identity
 * from the trusted stored initiator email (core) or a Keycloak token exchange (enterprise).
 */
public interface SignatureActorResolver {

    /** Provenance marker ({@code azp}) for the synthetic signer principal. */
    String AZP_SIGNATURE_LINK = "openfilz-signature-link";
    /** Provenance marker ({@code azp}) for the requester fallback principal. */
    String AZP_SIGNATURE_SERVICE = "openfilz-signature-service";

    Authentication signerAuthentication(SignatureRecipient recipient);

    Mono<Authentication> requesterAuthentication(SignatureEnvelope envelope);
}
