package org.openfilz.dms.e2e;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * TUS protocol e2e tests with MinIO (S3-compatible) storage backend.
 * Uses {@code storage.type=minio} configuration with a Testcontainers MinIO instance.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class TusMinioIT extends AbstractTusIT {

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

    public TusMinioIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("storage.type", () -> "minio");
        registry.add("storage.minio.endpoint", minio::getS3URL);
        registry.add("storage.minio.access-key", minio::getUserName);
        registry.add("storage.minio.secret-key", minio::getPassword);
        registry.add("openfilz.tus.enabled", () -> "true");
        registry.add("openfilz.security.no-auth", () -> false);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> keycloak.getAuthServerUrl() + "/realms/openfilz/protocol/openid-connect/certs");
    }
}
