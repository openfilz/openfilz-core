package org.openfilz.dms.security.session;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionRevocationJwtDecoderTest {

    private static final String SID = "session-1";

    private final RevokedSessionStore store = mock(RevokedSessionStore.class);

    private static Jwt.Builder token() {
        return Jwt.withTokenValue("token").header("alg", "RS256").subject("user");
    }

    private Mono<Jwt> decode(Jwt jwt) {
        ReactiveJwtDecoder delegate = token -> Mono.just(jwt);
        return new SessionRevocationJwtDecoder(delegate, store).decode("token");
    }

    @Test
    void aTokenOfASignedOutSessionIsRefused() {
        when(store.isRevoked(SID)).thenReturn(Mono.just(true));
        StepVerifier.create(decode(token().claim("sid", SID).claim("scope", "openid email profile").build()))
                .expectError(BadJwtException.class)
                .verify();
    }

    @Test
    void aTokenOfALiveSessionPasses() {
        when(store.isRevoked(SID)).thenReturn(Mono.just(false));
        Jwt jwt = token().claim("sid", SID).build();
        StepVerifier.create(decode(jwt)).expectNext(jwt).verifyComplete();
    }

    @Test
    void aTokenWithoutSessionIsNotLookedUp() {
        Jwt jwt = token().build();
        StepVerifier.create(decode(jwt)).expectNext(jwt).verifyComplete();
        verify(store, never()).isRevoked(anyString());
    }

    @Test
    void anOfflineSessionOutlivesTheBrowserSessionItSharesItsSidWith() {
        // Keycloak reported the sid because the browser session ended; the offline session did not.
        when(store.isRevoked(SID)).thenReturn(Mono.just(true));
        Jwt jwt = token().claim("sid", SID).claim("scope", "openid email profile offline_access").build();
        StepVerifier.create(decode(jwt)).expectNext(jwt).verifyComplete();
        verify(store, never()).isRevoked(anyString());
    }

    @Test
    void theScopeMayBeAList() {
        when(store.isRevoked(SID)).thenReturn(Mono.just(true));
        Jwt offline = token().claim("sid", SID).claim("scope", List.of("openid", "offline_access")).build();
        StepVerifier.create(decode(offline)).expectNext(offline).verifyComplete();
        StepVerifier.create(decode(token().claim("sid", SID).claim("scope", List.of("openid")).build()))
                .expectError(BadJwtException.class)
                .verify();
    }

    @Test
    void aScopeThatOnlyLooksLikeOfflineAccessDoesNotCount() {
        when(store.isRevoked(SID)).thenReturn(Mono.just(true));
        StepVerifier.create(decode(token().claim("sid", SID).claim("scope", "openid not_offline_access offline_access_x").build()))
                .expectError(BadJwtException.class)
                .verify();
    }
}
