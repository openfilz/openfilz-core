package org.openfilz.dms.e2e;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Version endpoints must answer HTTP 409 (VERSIONING_DISABLED) when versioning is
 * unavailable — here with the default local filesystem storage. The regular
 * replace-content flow must keep working unchanged.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class DocumentVersioningDisabledIT extends TestContainersBaseConfig {

    public DocumentVersioningDisabledIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @Test
    void whenVersioningDisabled_thenVersionEndpointsAnswer409() {
        UploadResponse uploaded = uploadDocument(newFileBuilder());
        Assertions.assertNotNull(uploaded);
        UUID id = uploaded.id();

        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/versions", id)
                .exchange()
                .expectStatus().isEqualTo(409);

        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/versions/{versionId}/download", id, "some-version")
                .exchange()
                .expectStatus().isEqualTo(409);

        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/versions/{versionId}/restore", id, "some-version")
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void whenVersioningDisabled_thenReplaceContentStillWorks() {
        UploadResponse uploaded = uploadDocument(newFileBuilder());
        Assertions.assertNotNull(uploaded);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content", uploaded.id())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk();
    }
}
