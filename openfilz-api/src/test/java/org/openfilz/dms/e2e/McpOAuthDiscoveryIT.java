package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * OAuth 2.1 discovery for the MCP endpoint (RFC 9728 + the MCP authorization spec).
 * <p>
 * A remote MCP host (Claude Desktop, claude.ai, an IDE connector) does not carry a pre-agreed
 * bearer token — it discovers how to obtain one. The flow this suite pins:
 * <ol>
 *   <li>The host calls {@code /mcp} with no token and gets {@code 401} carrying
 *       {@code WWW-Authenticate: Bearer resource_metadata="…/.well-known/oauth-protected-resource"}.</li>
 *   <li>It fetches that metadata (unauthenticated) and learns which authorization server protects
 *       the resource — our Keycloak realm.</li>
 *   <li>It then talks to Keycloak directly (that server's own metadata, authorization, token).</li>
 * </ol>
 * Steps 1–2 are OpenFilz's responsibility and are what this suite covers; step 3 is Keycloak's.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class McpOAuthDiscoveryIT extends AbstractMcpIT {

    private static final String PROTECTED_RESOURCE_METADATA = "/.well-known/oauth-protected-resource";
    private static final String AUTHORIZATION_SERVER_METADATA = "/.well-known/oauth-authorization-server";

    public McpOAuthDiscoveryIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    /** Point the discovery metadata at the same Keycloak realm the resource server validates against. */
    @DynamicPropertySource
    static void registerAuthorizationServer(DynamicPropertyRegistry registry) {
        registry.add("openfilz.mcp.authorization-server-url",
                () -> keycloak.getAuthServerUrl() + "/realms/openfilz");
    }

    @Test
    @DisplayName("protected-resource metadata is served without a token and names the Keycloak realm")
    void protectedResourceMetadataIsPublicAndCorrect() {
        String authServer = keycloak.getAuthServerUrl() + "/realms/openfilz";

        webTestClient.get().uri(PROTECTED_RESOURCE_METADATA)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.resource").value(r -> org.assertj.core.api.Assertions
                        .assertThat((String) r).endsWith("/mcp"))
                .jsonPath("$.authorization_servers[0]").isEqualTo(authServer)
                .jsonPath("$.bearer_methods_supported[0]").isEqualTo("header");
    }

    @Test
    @DisplayName("an unauthenticated /mcp call points the client at the resource metadata")
    void unauthenticatedMcpCallAdvertisesResourceMetadata() {
        webTestClient.post().uri(MCP_ENDPOINT)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE, h -> org.assertj.core.api.Assertions
                        .assertThat(h)
                        .as("the 401 must tell the client where the protected-resource metadata is")
                        .contains("Bearer")
                        .contains("resource_metadata=")
                        .contains(PROTECTED_RESOURCE_METADATA));
    }

    @Test
    @DisplayName("authorization-server metadata redirects to the realm's OIDC discovery document")
    void authorizationServerMetadataRedirectsToKeycloak() {
        // Some clients look for the AS metadata at the resource server's base. We forward them to
        // Keycloak's own document rather than duplicating it.
        webTestClient.get().uri(AUTHORIZATION_SERVER_METADATA)
                .exchange()
                .expectStatus().is3xxRedirection()
                .expectHeader().value(HttpHeaders.LOCATION, location -> org.assertj.core.api.Assertions
                        .assertThat(location)
                        .contains("/realms/openfilz")
                        .endsWith("/.well-known/openid-configuration"));
    }

    @Test
    @DisplayName("discovery metadata does not require the MCP bearer scope to read")
    void metadataIsReadableWithoutAnyToken() {
        // Redundant with the first test on purpose: metadata that needed a token would make the
        // whole discovery flow circular. Assert it plainly.
        webTestClient.get().uri(PROTECTED_RESOURCE_METADATA)
                .exchange()
                .expectStatus().isOk();
    }
}
