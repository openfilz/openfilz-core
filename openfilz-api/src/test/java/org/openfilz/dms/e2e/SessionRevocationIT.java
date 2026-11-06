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
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
