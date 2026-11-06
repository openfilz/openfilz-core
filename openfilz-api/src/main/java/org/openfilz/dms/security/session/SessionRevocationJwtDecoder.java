package org.openfilz.dms.security.session;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * Refuses an access token whose Keycloak session ({@code sid} claim) was signed out, although its
 * signature and expiry are still good: after a password change that signed out the other devices,
 * those devices must lose access at once, not when their token expires. A token without
 * {@code sid} is judged by the delegate alone.
 */
public class SessionRevocationJwtDecoder implements ReactiveJwtDecoder {

    public static final String SID_CLAIM = "sid";

    private final ReactiveJwtDecoder delegate;
    private final RevokedSessionStore revokedSessions;

    public SessionRevocationJwtDecoder(ReactiveJwtDecoder delegate, RevokedSessionStore revokedSessions) {
        this.delegate = delegate;
        this.revokedSessions = revokedSessions;
    }

    @Override
    public Mono<Jwt> decode(String token) throws JwtException {
        return delegate.decode(token).flatMap(jwt -> {
            String sid = jwt.getClaimAsString(SID_CLAIM);
            if (sid == null || sid.isBlank()) {
                return Mono.just(jwt);
            }
            return revokedSessions.isRevoked(sid).flatMap(isRevoked -> isRevoked
                    ? Mono.error(new BadJwtException("The session this token belongs to was signed out"))
                    : Mono.just(jwt));
        });
    }
}
