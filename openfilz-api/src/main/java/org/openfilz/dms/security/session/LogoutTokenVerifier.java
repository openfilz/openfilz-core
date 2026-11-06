package org.openfilz.dms.security.session;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Checks an OIDC back-channel logout token (OpenID Connect Back-Channel Logout 1.0, §2.6): signed
 * by the realm's key (the JWK set the access tokens are checked against), carrying the back-channel
 * logout event and a {@code sid}, and no {@code nonce}, so that an ID or access token, signed by
 * the same key, cannot be replayed here to sign someone out.
 */
@Component
public class LogoutTokenVerifier {

    static final String BACKCHANNEL_LOGOUT_EVENT = "http://schemas.openid.net/event/backchannel-logout";

    private final NimbusReactiveJwtDecoder decoder;

    @Autowired
    public LogoutTokenVerifier(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri) {
        this(jwkSetUri == null || jwkSetUri.isBlank() ? null : NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build());
    }

    /** {@code signatureDecoder} checks the signature; its validator is replaced by the logout-token one. */
    LogoutTokenVerifier(NimbusReactiveJwtDecoder signatureDecoder) {
        if (signatureDecoder != null) {
            signatureDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    new JwtTimestampValidator(), new LogoutTokenClaimsValidator()));
        }
        this.decoder = signatureDecoder;
    }

    /** The {@code sid} the token signs out, or an error when it is not a valid logout token. */
    public Mono<String> verify(String logoutToken) {
        if (decoder == null) {
            return Mono.error(new IllegalStateException("No JWK set is configured to check logout tokens"));
        }
        return Mono.defer(() -> decoder.decode(logoutToken))
                .map(jwt -> jwt.getClaimAsString(SessionRevocationJwtDecoder.SID_CLAIM));
    }

    static final class LogoutTokenClaimsValidator implements OAuth2TokenValidator<Jwt> {

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            Object events = jwt.getClaims().get("events");
            if (!(events instanceof Map<?, ?> map) || !map.containsKey(BACKCHANNEL_LOGOUT_EVENT)) {
                return invalid("The logout token has no back-channel logout event");
            }
            if (jwt.getClaims().containsKey("nonce")) {
                return invalid("A logout token must not carry a nonce");
            }
            String sid = jwt.getClaimAsString(SessionRevocationJwtDecoder.SID_CLAIM);
            if (sid == null || sid.isBlank()) {
                return invalid("The logout token names no session (sid)");
            }
            return OAuth2TokenValidatorResult.success();
        }

        private static OAuth2TokenValidatorResult invalid(String description) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_request", description, null));
        }
    }
}
