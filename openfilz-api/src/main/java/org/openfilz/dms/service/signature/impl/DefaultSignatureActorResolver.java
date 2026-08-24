package org.openfilz.dms.service.signature.impl;

import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureRecipient;
import org.openfilz.dms.service.signature.SignatureActorResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Core resolver: synthetic {@link JwtAuthenticationToken}s whose {@code email} claim is the
 * trusted identity stored on the row (signer: the recipient row resolved by a validated
 * token; requester: the initiator email captured from the JWT at send time). Core's
 * {@code UserInfoService} reads the principal email from that claim, so audit rows are
 * attributed correctly without any Keycloak round-trip.
 */
@Service
public class DefaultSignatureActorResolver implements SignatureActorResolver {

    @Override
    public Authentication signerAuthentication(SignatureRecipient recipient) {
        return synthetic(recipient.getRecipientEmail(), recipient.getRecipientName(), AZP_SIGNATURE_LINK);
    }

    @Override
    public Mono<Authentication> requesterAuthentication(SignatureEnvelope envelope) {
        return Mono.just(synthetic(envelope.getInitiatorEmail(), null, AZP_SIGNATURE_SERVICE));
    }

    static Authentication synthetic(String email, String name, String azp) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("synthetic")
                .header("alg", "none")
                .claim("sub", email)
                .claim("email", email)
                .claim("preferred_username", email)
                .claim("name", name == null ? email : name)
                .claim("azp", azp)
                .claim("realm_access", Map.of("roles", List.of()))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(), email);
    }
}
