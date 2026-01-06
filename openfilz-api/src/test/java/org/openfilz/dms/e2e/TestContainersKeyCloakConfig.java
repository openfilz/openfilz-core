package org.openfilz.dms.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.junit.jupiter.Container;

import java.io.IOException;

public abstract class TestContainersKeyCloakConfig extends TestContainersBaseConfig {

    @Container
    static final KeycloakContainer keycloak = new KeycloakContainer()
            .withRealmImportFile("keycloak/realm-export.json").withReuse(true);

    public TestContainersKeyCloakConfig(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    protected static String getAccessToken(String username) {
        return WebClient.builder()
                .baseUrl(keycloak.getAuthServerUrl())
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
