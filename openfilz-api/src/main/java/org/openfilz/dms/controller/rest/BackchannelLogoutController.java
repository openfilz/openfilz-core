package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.security.session.LogoutTokenVerifier;
import org.openfilz.dms.security.session.RevokedSessionStore;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * OIDC back-channel logout receiver (OpenID Connect Back-Channel Logout 1.0). Keycloak posts a
 * signed logout token here whenever a session ends: a password change signing out the other
 * devices, a sign-out, an admin ending the session. The session's {@code sid} is recorded so its
 * access tokens, still validly signed, are refused from then on.
 *
 * <p>Called by Keycloak, never by a user: unauthenticated (the logout token's signature is the
 * proof, see {@link LogoutTokenVerifier}) and kept out of the OpenAPI document.
 */
@Slf4j
@Hidden
@RestController
@RequiredArgsConstructor
public class BackchannelLogoutController {

    public static final String BACKCHANNEL_LOGOUT_PATH = RestApiVersion.API_PREFIX + "/auth/backchannel-logout";

    private final LogoutTokenVerifier logoutTokenVerifier;
    private final RevokedSessionStore revokedSessionStore;

    @PostMapping(value = BACKCHANNEL_LOGOUT_PATH, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Mono<ResponseEntity<Void>> backchannelLogout(ServerWebExchange exchange) {
        return exchange.getFormData()
                .mapNotNull(form -> form.getFirst("logout_token"))
                .filter(token -> !token.isBlank())
                .flatMap(token -> logoutTokenVerifier.verify(token)
                        .onErrorResume(e -> {
                            // Anyone can post here: a refused token is not worth a warning per request.
                            log.debug("Back-channel logout refused: {}", e.getMessage());
                            return Mono.empty();
                        })
                        .flatMap(sid -> revokedSessionStore.revoke(sid)
                                .doOnSuccess(v -> log.info("Session {} signed out by Keycloak: its tokens are refused", sid))
                                .thenReturn(ok())
                                .onErrorResume(e -> {
                                    // Refused on this replica already (memory); the others never learn of it.
                                    log.error("Session {} was signed out by Keycloak but could not be recorded: "
                                            + "the other API replicas keep accepting its tokens until they expire", sid, e);
                                    return Mono.just(badRequest());
                                })))
                .defaultIfEmpty(badRequest());
    }

    // Spec §2.8: the response must not be cached; 400 also when the logout could not be carried out.
    private static ResponseEntity<Void> ok() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }

    private static ResponseEntity<Void> badRequest() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
    }
}
