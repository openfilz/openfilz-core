package org.openfilz.dms.e2e;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.DocumentInfo;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class LocalStorageWithSignatureIT extends AbstractStorageWithSignatureIT {

    public LocalStorageWithSignatureIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.calculate-checksum", () -> Boolean.TRUE);
    }

    @Test
    void whenReplaceContentWithSameFile_thenChecksumUnchanged() {
        // Upload original file
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse originalUploadResponse = uploadDocument(builder);

        Assertions.assertNotNull(originalUploadResponse);

        // Get original document info with checksum
        DocumentInfo originalInfo = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                                .queryParam("withMetadata", true)
                                .build(originalUploadResponse.id().toString()))
                .exchange()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(originalInfo);
        String originalChecksum = (String) originalInfo.metadata().get("sha256");
        Assertions.assertNotNull(originalChecksum);
        Assertions.assertEquals(test_file_1_sql_sha, originalChecksum);

        // Replace content with the same file
        MultipartBodyBuilder replaceBuilder = new MultipartBodyBuilder();
        replaceBuilder.part("file", new ClassPathResource("test_file_1.sql"));

        getWebTestClient().put().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content")
                        .build(originalUploadResponse.id().toString()))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(replaceBuilder.build()))
                .exchange()
                .expectStatus().isOk();

        // Get document info after replacement
        DocumentInfo afterReplaceInfo = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                                .queryParam("withMetadata", true)
                                .build(originalUploadResponse.id().toString()))
                .exchange()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(afterReplaceInfo);
        String afterReplaceChecksum = (String) afterReplaceInfo.metadata().get("sha256");

        // Checksum should remain the same since we replaced with the same file
        Assertions.assertEquals(originalChecksum, afterReplaceChecksum);
        Assertions.assertEquals(test_file_1_sql_sha, afterReplaceChecksum);
    }

    @Test
    void whenReplaceContentWithDifferentFile_thenChecksumRecalculated() {
        // Upload original file (test_file_1.sql)
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse originalUploadResponse = uploadDocument(builder);

        Assertions.assertNotNull(originalUploadResponse);

        // Get original document info with checksum
        DocumentInfo originalInfo = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                                .queryParam("withMetadata", true)
                                .build(originalUploadResponse.id().toString()))
                .exchange()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(originalInfo);
        String originalChecksum = (String) originalInfo.metadata().get("sha256");
        Assertions.assertNotNull(originalChecksum);
        Assertions.assertEquals(test_file_1_sql_sha, originalChecksum);

        // Replace content with a different file (test.txt)
        MultipartBodyBuilder replaceBuilder = new MultipartBodyBuilder();
        replaceBuilder.part("file", new ClassPathResource("test.txt"));

        getWebTestClient().put().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content")
                        .build(originalUploadResponse.id().toString()))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(replaceBuilder.build()))
                .exchange()
                .expectStatus().isOk();

        // Get document info after replacement
        DocumentInfo afterReplaceInfo = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                                .queryParam("withMetadata", true)
                                .build(originalUploadResponse.id().toString()))
                .exchange()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(afterReplaceInfo);
        String afterReplaceChecksum = (String) afterReplaceInfo.metadata().get("sha256");

        // Checksum should be recalculated to match the new file's checksum
        Assertions.assertNotNull(afterReplaceChecksum);
        Assertions.assertNotEquals(originalChecksum, afterReplaceChecksum);
        Assertions.assertEquals(test_txt_sha, afterReplaceChecksum);
    }

}
