package org.openfilz.sdk.samples;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.io.IOException;
import java.nio.file.Files;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class SdkSamplesBaseConfig {

    @Value("${local.server.port}")
    protected int serverPort;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.1").withReuse(true);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> String.format("r2dbc:postgresql://%s:%d/%s",
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getDatabaseName()));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);

        registry.add("spring.flyway.url", () -> String.format("jdbc:postgresql://%s:%d/%s",
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getDatabaseName()));
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);

        // Disable authentication for SDK sample tests
        registry.add("openfilz.security.no-auth", () -> "true");

        // Use local file storage
        registry.add("storage.type", () -> "local");
        registry.add("storage.local.base-path", () -> {
            try {
                return Files.createTempDirectory("sdk-samples-storage").toString();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    protected String getApiBaseUrl() {
        return "http://localhost:" + serverPort;
    }
}
