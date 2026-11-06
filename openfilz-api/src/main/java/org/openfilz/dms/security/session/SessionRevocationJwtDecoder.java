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
 *
 * <p>So is a token issued under the {@code offline_access} scope. A client that signs in through a
 * browser already signed in to OpenFilz (an MCP connector, a sync client) gets an offline session
 * carrying the <em>same</em> {@code sid} as the browser session. When the browser session ends,
 * Keycloak reports that {@code sid}, yet the offline session lives on and its refresh token keeps
 * producing tokens with it: refusing them would lock that client out for as long as the revocation
 * is kept, each time its user signs out of the web application, and then let it back in. Keycloak
 * sends no back-channel logout that tells the two apart, so the end of an offline session is not
 * seen here; its access tokens stop at their expiry.
 */
public class SessionRevocationJwtDecoder implements ReactiveJwtDecoder {

    public static final String SID_CLAIM = "sid";

    static final String SCOPE_CLAIM = "scope";
    static final String OFFLINE_ACCESS_SCOPE = "offline_access";

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
            if (sid == null || sid.isBlank() || isIssuedForAnOfflineSession(jwt)) {
                return Mono.just(jwt);
            }
            return revokedSessions.isRevoked(sid).flatMap(isRevoked -> isRevoked
                    ? Mono.error(new BadJwtException("The session this token belongs to was signed out"))
                    : Mono.just(jwt));
        });
    }

    /** {@code scope} is a space-separated list (RFC 6749 §3.3); some issuers send a JSON array. */
    private static boolean isIssuedForAnOfflineSession(Jwt jwt) {
        Object scope = jwt.getClaims().get(SCOPE_CLAIM);
        if (scope instanceof String scopes) {
            for (String name : scopes.split(" ")) {
                if (OFFLINE_ACCESS_SCOPE.equals(name)) {
                    return true;
                }
            }
            return false;
        }
        return scope instanceof Iterable<?> names && containsOfflineAccess(names);
    }

    private static boolean containsOfflineAccess(Iterable<?> names) {
        for (Object name : names) {
            if (OFFLINE_ACCESS_SCOPE.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
