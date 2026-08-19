package org.openfilz.dms.e2e;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Base configuration class for tests that require KeyCloak authentication.
 * Uses a shared KeyCloak container singleton to avoid starting a new container
 * for each test class.
 */
public abstract class TestContainersKeyCloakConfig extends TestContainersBaseConfig {

    // Reference to the shared container for subclasses that need direct access
    protected static final KeycloakContainer keycloak = SharedKeyCloakContainer.getInstance();

    public TestContainersKeyCloakConfig(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    protected static String getAccessToken(String username) {
        return SharedKeyCloakContainer.getAccessToken(username);
    }

}
