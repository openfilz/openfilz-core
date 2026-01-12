package org.openfilz.dms.e2e;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Base configuration class for tests that require KeyCloak authentication.
 * Uses a shared KeyCloak container singleton to avoid starting a new container
 * for each test class.
 */
public abstract class TestContainersKeyCloakConfig extends TestContainersBaseConfig {

    // Reference to the shared container for subclasses that need direct access
    protected static final KeycloakContainer keycloak = SharedKeyCloakContainer.getInstance();

    public TestContainersKeyCloakConfig(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    protected static String getAccessToken(String username) {
        return SharedKeyCloakContainer.getAccessToken(username);
    }

}
