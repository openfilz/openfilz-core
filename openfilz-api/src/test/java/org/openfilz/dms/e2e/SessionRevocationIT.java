package org.openfilz.dms.e2e;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.controller.rest.BackchannelLogoutController;
import org.openfilz.dms.security.session.RevokedSessionStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * A password change signs the user's other Keycloak sessions out, and Keycloak tells the API
 * through the back-channel logout endpoint. These tests use real Keycloak tokens: once a session
 * is recorded as signed out, its still-valid access token is refused, other sessions are not, and
 * the endpoint itself accepts nothing but a real logout token.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class SessionRevocationIT extends TestContainersKeyCloakConfig {

    private static final String SETTINGS = RestApiVersion.API_PREFIX + "/settings";

    @Autowired
    private RevokedSessionStore revokedSessionStore;

    @Autowired
    private DatabaseClient databaseClient;

    SessionRevocationIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", SharedKeyCloakContainer::getJwkSetUri);
        registry.add("openfilz.security.no-auth", () -> false);
    }

    private static String sidOf(String accessToken) {
        String payload = new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
        var sid = JsonMapper.builder().build().readTree(payload).get("sid");
        assertNotNull(sid, "Keycloak access tokens are expected to carry the sid claim");
        return sid.asString();
    }

    private int settingsStatus(String accessToken) {
        return webTestClient.get().uri(SETTINGS)
                .header("Authorization", "Bearer " + accessToken)
                .exchange()
                .returnResult(String.class)
                .getStatus().value();
    }

    @Test
    void aSignedOutSessionLosesAccessAtOnceWhileOtherSessionsKeepIt() {
        // Two sessions of the same user, as in two browsers.
        String browserA = getAccessToken("contributor-user");
        String browserB = getAccessToken("contributor-user");
        assertEquals(200, settingsStatus(browserA));
        assertEquals(200, settingsStatus(browserB));

        // Browser A changes the password: Keycloak signs B out and notifies the API.
        revokedSessionStore.revoke(sidOf(browserB)).block();

        assertEquals(401, settingsStatus(browserB), "the signed-out session's token must be refused");
        assertEquals(200, settingsStatus(browserA), "the session that changed the password keeps working");

        // Recorded in the database, so every replica refuses it (not only this one's cache).
        Long rows = databaseClient.sql("SELECT count(*) AS n FROM revoked_auth_session WHERE sid = :sid")
                .bind("sid", sidOf(browserB))
                .map((row, meta) -> row.get("n", Long.class))
                .one().block();
        assertEquals(1L, rows);
    }

    /**
     * A token of the {@code openfilz-web} client asking for {@code offline_access} explicitly: the core
     * test realm grants it by default. A realm whose users may not hold offline tokens (the enterprise
     * test realm has no {@code offline_access} role) cannot exercise the case, so it is skipped there;
     * {@code SessionRevocationJwtDecoderTest} covers the rule either way.
     */
    private static String offlineScopedAccessToken(String username) {
        String response = WebClient.create(SharedKeyCloakContainer.getAuthServerUrl()).post()
                .uri("/realms/openfilz/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "password")
                        .with("client_id", "openfilz-web")
                        .with("scope", "openid offline_access")
                        .with("username", username)
                        .with("password", "password"))
                .exchangeToMono(r -> r.bodyToMono(String.class))
                .block();
        var json = JsonMapper.builder().build().readTree(response);
        assumeTrue(json.get("access_token") != null,
                "this realm issues no offline tokens to " + username + ": " + response);
        return json.get("access_token").asString();
    }

    @Test
    void anOfflineSessionKeepsAccessWhenTheBrowserSessionSharingItsSidEnds() {
        // A connector or a sync client signed in through a browser already signed in to the web
        // application: Keycloak gives its offline session the browser session's sid, and keeps
        // issuing tokens with that sid after the browser session ended.
        String offline = offlineScopedAccessToken("contributor-user");
        String payload = new String(Base64.getUrlDecoder().decode(offline.split("\\.")[1]), StandardCharsets.UTF_8);
        String scope = JsonMapper.builder().build().readTree(payload).get("scope").asString();
        assertTrue(scope.contains("offline_access"), "expected an offline_access token, got scope: " + scope);
        assertEquals(200, settingsStatus(offline));

        // The browser session ends: Keycloak reports the sid they share.
        revokedSessionStore.revoke(sidOf(offline)).block();

        assertEquals(200, settingsStatus(offline), "the offline session did not end: its token must still be accepted");
    }

    @Test
    void theEndpointRefusesAnAccessTokenPresentedAsALogoutToken() {
        // Signed by the realm key, but it is not a logout token: it must not sign anyone out.
        String token = getAccessToken("reader-user");
        webTestClient.post().uri(BackchannelLogoutController.BACKCHANNEL_LOGOUT_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("logout_token", token))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore());
        assertEquals(200, settingsStatus(token));
    }

    @Test
    void theEndpointRefusesGarbageAndAMissingToken() {
        webTestClient.post().uri(BackchannelLogoutController.BACKCHANNEL_LOGOUT_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("logout_token", "not-a-jwt"))
                .exchange()
                .expectStatus().isBadRequest();
        webTestClient.post().uri(BackchannelLogoutController.BACKCHANNEL_LOGOUT_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("other", "x"))
                .exchange()
                .expectStatus().isBadRequest();
    }
}
