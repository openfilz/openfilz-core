package org.openfilz.dms.controller.rest;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.security.session.LogoutTokenVerifier;
import org.openfilz.dms.security.session.RevokedSessionStore;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackchannelLogoutControllerTest {

    private final LogoutTokenVerifier verifier = mock(LogoutTokenVerifier.class);
    private final RevokedSessionStore store = mock(RevokedSessionStore.class);
    private final WebTestClient client = WebTestClient
            .bindToController(new BackchannelLogoutController(verifier, store))
            .build();

    private WebTestClient.ResponseSpec post(String logoutToken) {
        return client.post().uri(BackchannelLogoutController.BACKCHANNEL_LOGOUT_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("logout_token", logoutToken))
                .exchange();
    }

    @Test
    void aValidLogoutTokenRevokesItsSession() {
        when(verifier.verify("good")).thenReturn(Mono.just("session-1"));
        when(store.revoke("session-1")).thenReturn(Mono.empty());

        post("good").expectStatus().isOk().expectHeader().cacheControl(CacheControl.noStore());

        verify(store).revoke("session-1");
    }

    @Test
    void aRefusedTokenRevokesNothing() {
        when(verifier.verify("bad")).thenReturn(Mono.error(new BadJwtException("not a logout token")));

        post("bad").expectStatus().isBadRequest().expectHeader().cacheControl(CacheControl.noStore());

        verify(store, never()).revoke(anyString());
    }

    @Test
    void aLogoutThatCouldNotBeRecordedIsReportedToKeycloak() {
        when(verifier.verify("good")).thenReturn(Mono.just("session-1"));
        when(store.revoke("session-1")).thenReturn(Mono.error(new IllegalStateException("database down")));

        post("good").expectStatus().isBadRequest().expectHeader().cacheControl(CacheControl.noStore());
    }
}
