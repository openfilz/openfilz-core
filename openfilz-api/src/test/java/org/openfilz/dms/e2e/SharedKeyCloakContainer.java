package org.openfilz.dms.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;

/**
 * Singleton container for KeyCloak to be shared across all test classes.
 * This avoids starting a new KeyCloak container for each test class,
 * significantly reducing test execution time.
 */
public final class SharedKeyCloakContainer {

    private static final KeycloakContainer KEYCLOAK_CONTAINER;

    static {
        KEYCLOAK_CONTAINER = new KeycloakContainer()
                .withRealmImportFile("keycloak/realm-export.json")
                .withEnv("KEYCLOAK_DEFAULT_ROLE", "READER")
                .withEnv("KEYCLOAK_DEFAULT_GROUP", "GED/READER")
                .withEnv("KC_HTTP_ENABLED", "true")
                .withReuse(true);
        KEYCLOAK_CONTAINER.start();
    }

    private SharedKeyCloakContainer() {
        // Prevent instantiation
    }

    public static KeycloakContainer getInstance() {
        return KEYCLOAK_CONTAINER;
    }

    public static String getAuthServerUrl() {
        return KEYCLOAK_CONTAINER.getAuthServerUrl();
    }

    public static String getJwkSetUri() {
        return getAuthServerUrl() + "/realms/openfilz/protocol/openid-connect/certs";
    }

    public static String getAccessToken(String username) {
        return WebClient.builder()
                .baseUrl(getAuthServerUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .build()
                .post()
                .uri("/realms/openfilz/protocol/openid-connect/token")
                .body(
                        BodyInserters.fromFormData("grant_type", "password")
                                .with("client_id", "test-client")
                                .with("client_secret", "test-client-secret")
                                .with("username", username)
                                .with("password", "password")
                )
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> {
                    try {
                        return new ObjectMapper().readTree(response).get("access_token").asText();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .block();
    }
}
