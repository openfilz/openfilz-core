package org.openfilz.dms.e2e;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.DocumentInfo;
import org.openfilz.dms.dto.response.DocumentVersionInfo;
import org.openfilz.dms.dto.response.RestoreVersionResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Restore + checksum interplay: after restoring a previous version, the stored
 * sha256 metadata must be recomputed and equal the restored content's checksum.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class DocumentVersioningChecksumIT extends TestContainersBaseConfig {

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

    public DocumentVersioningChecksumIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("storage.minio.endpoint", minio::getS3URL);
        registry.add("storage.minio.access-key", minio::getUserName);
        registry.add("storage.minio.secret-key", minio::getPassword);
        registry.add("storage.type", () -> "minio");
        registry.add("storage.minio.versioning-enabled", () -> true);
        registry.add("openfilz.calculate-checksum", () -> Boolean.TRUE);
    }

    @Test
    void whenRestoreVersion_thenChecksumRecomputedToOriginal() {
        // 1. Upload original and capture its checksum
        UploadResponse uploaded = uploadDocument(newFileBuilder());
        Assertions.assertNotNull(uploaded);
        UUID id = uploaded.id();
        String originalChecksum = getChecksum(id);
        Assertions.assertNotNull(originalChecksum);

        // 2. Replace with a different file: checksum changes
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content", id)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk();
        String replacedChecksum = getChecksum(id);
        Assertions.assertNotEquals(originalChecksum, replacedChecksum);

        // 3. Restore the original version: checksum must be recomputed back
        List<DocumentVersionInfo> versions = getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/versions", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<DocumentVersionInfo>>() {})
                .returnResult().getResponseBody();
        Assertions.assertEquals(2, versions.size());
        DocumentVersionInfo oldest = versions.getLast();

        RestoreVersionResponse response = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/versions/{versionId}/restore", id, oldest.versionId())
                .exchange()
                .expectStatus().isOk()
                .expectBody(RestoreVersionResponse.class)
                .returnResult().getResponseBody();
        Assertions.assertNotNull(response.newVersionId());

        Assertions.assertEquals(originalChecksum, getChecksum(id),
                "Stored checksum must equal the restored version's checksum");
    }

    private String getChecksum(UUID id) {
        DocumentInfo info = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                                .queryParam("withMetadata", true)
                                .build(id))
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();
        return info.metadata() != null ? (String) info.metadata().get("sha256") : null;
    }
}
