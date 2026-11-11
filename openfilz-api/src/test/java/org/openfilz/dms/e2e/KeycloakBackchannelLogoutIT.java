package org.openfilz.dms.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.controller.rest.BackchannelLogoutController;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * The whole chain, with Keycloak making the call itself: a session is signed out in Keycloak,
 * Keycloak posts its logout token to the API's back-channel logout URL, and the API refuses the
 * access token that session still holds. {@link SessionRevocationIT} posts the token itself, so it
 * cannot tell when Keycloak never sends one.
 * <p>
 * The client gets the settings an installation needs, for the time of the test: the URL (it
 * carries the port of this test's server, which Keycloak reaches through
 * {@code host.docker.internal}, see {@link SharedKeyCloakContainer}), "Backchannel logout session
 * required" and "Front channel logout" off. Keycloak 26.7 never calls the back-channel URL of a client that has that switch on: it only
 * logs "Some clients have not been logged out".
 * <p>
 * The sessions are those of {@code test-client}: the test realm gives {@code openfilz-web} the
 * {@code offline_access} scope by default, and a token issued under that scope is never refused
 * (see {@code SessionRevocationJwtDecoder}).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class KeycloakBackchannelLogoutIT extends TestContainersKeyCloakConfig {

    private static final String SETTINGS = RestApiVersion.API_PREFIX + "/settings";
    private static final String REALM = "openfilz";
    private static final String CLIENT = "test-client";
    private static final String CLIENT_SECRET = "test-client-secret";
    private static final String LOGOUT_URL_ATTRIBUTE = "backchannel.logout.url";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WebClient keycloakHttp = WebClient.create(SharedKeyCloakContainer.getAuthServerUrl());

    @LocalServerPort
    private int serverPort;

    /** The client's switch as the realm import left it, put back after the test. */
    private boolean frontChannelLogoutBefore;

    KeycloakBackchannelLogoutIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", SharedKeyCloakContainer::getJwkSetUri);
        registry.add("openfilz.security.no-auth", () -> false);
    }

    @BeforeEach
    void pointKeycloakAtThisServer() {
        frontChannelLogoutBefore = client(adminToken()).path("frontchannelLogout").asBoolean();
        configureLogout("http://host.docker.internal:" + serverPort + BackchannelLogoutController.BACKCHANNEL_LOGOUT_PATH, false);
    }

    @AfterEach
    void clearTheLogoutUrl() {
        // The Keycloak container is shared with the other test classes (and reused between runs):
        // it must not keep calling a server that is gone. Keycloak drops an empty attribute.
        configureLogout("", frontChannelLogoutBefore);
    }

    @Test
    void keycloakTellsTheApiWhenASessionIsSignedOut() {
        assertFalse(client(adminToken()).get("frontchannelLogout").asBoolean());

        // Two sessions of the same user, as in two browsers.
        JsonNode signedOut = signIn("contributor-user");
        JsonNode kept = signIn("contributor-user");
        String signedOutToken = signedOut.get("access_token").asString();
        String keptToken = kept.get("access_token").asString();
        assertEquals(200, settingsStatus(signedOutToken));
        assertEquals(200, settingsStatus(keptToken));

        // Keycloak ends the first session. It makes the back-channel call before it answers.
        HttpStatusCode logout = keycloakHttp.post()
                .uri("/realms/" + REALM + "/protocol/openid-connect/logout")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("client_id", CLIENT)
                        .with("client_secret", CLIENT_SECRET)
                        .with("refresh_token", signedOut.get("refresh_token").asString()))
                .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode()))
                .block();
        assertNotNull(logout);
        assertEquals(204, logout.value());

        assertEquals(401, settingsStatus(signedOutToken),
                "Keycloak did not tell the API that the session ended: is Front channel logout on for "
                        + CLIENT + ", or can the Keycloak container not reach host.docker.internal:" + serverPort + "?");
        assertEquals(200, settingsStatus(keptToken), "the other session did not end: its token must still be accepted");
    }

    private int settingsStatus(String accessToken) {
        return webTestClient.get().uri(SETTINGS)
                .header("Authorization", "Bearer " + accessToken)
                .exchange()
                .returnResult(String.class)
                .getStatus().value();
    }

    /** A session of the client: the access token and the refresh token that ends it. */
    private JsonNode signIn(String username) {
        JsonNode tokens = token(REALM, BodyInserters.fromFormData("grant_type", "password")
                .with("client_id", CLIENT)
                .with("client_secret", CLIENT_SECRET)
                .with("scope", "openid")
                .with("username", username)
                .with("password", "password"));
        assertNotNull(tokens.get("refresh_token"), "no refresh token for " + username + ": " + tokens);
        return tokens;
    }

    private String adminToken() {
        return token("master", BodyInserters.fromFormData("grant_type", "password")
                .with("client_id", "admin-cli")
                .with("username", keycloak.getAdminUsername())
                .with("password", keycloak.getAdminPassword()))
                .get("access_token").asString();
    }

    private JsonNode token(String realm, BodyInserters.FormInserter<String> form) {
        String response = keycloakHttp.post()
                .uri("/realms/" + realm + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .exchangeToMono(r -> r.bodyToMono(String.class))
                .block();
        JsonNode json = JSON.readTree(response);
        assertNotNull(json.get("access_token"), "Keycloak issued no token: " + response);
        return json;
    }

    private JsonNode client(String adminToken) {
        String response = keycloakHttp.get()
                .uri(uri -> uri.path("/admin/realms/" + REALM + "/clients").queryParam("clientId", CLIENT).build())
                .headers(headers -> headers.setBearerAuth(adminToken))
                .retrieve()
                .bodyToMono(String.class)
                .block();
        JsonNode clients = JSON.readTree(response);
        assertEquals(1, clients.size(), "expected one " + CLIENT + " client: " + response);
        return clients.get(0);
    }

    private void configureLogout(String url, boolean frontChannelLogout) {
        String adminToken = adminToken();
        ObjectNode client = (ObjectNode) client(adminToken);
        client.put("frontchannelLogout", frontChannelLogout);
        ObjectNode attributes = client.withObject("attributes");
        attributes.put(LOGOUT_URL_ATTRIBUTE, url);
        // Without it the logout token names no session, and the API refuses a token without a sid.
        attributes.put("backchannel.logout.session.required", url.isEmpty() ? "" : "true");
        keycloakHttp.put()
                .uri("/admin/realms/" + REALM + "/clients/" + client.get("id").asString())
                .headers(headers -> headers.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(JSON.writeValueAsString(client))
                .retrieve()
                .toBodilessEntity()
                .block();
    }
}
