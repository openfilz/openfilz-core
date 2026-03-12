package org.openfilz.dms.e2e;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Integration test that extracts the OpenAPI specification from the running application.
 * The generated spec is written to target/openapi/openfilz-api.json and attached
 * as a Maven artifact for consumption by the SDK generation modules.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class OpenApiSpecExtractionIT extends TestContainersBaseConfig {

    public OpenApiSpecExtractionIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @Test
    void extractOpenApiSpec() throws IOException {
        byte[] spec = webTestClient.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        Path outputDir = Paths.get("target/openapi");
        Files.createDirectories(outputDir);
        Files.write(outputDir.resolve("openfilz-api.json"), spec);
    }
}
