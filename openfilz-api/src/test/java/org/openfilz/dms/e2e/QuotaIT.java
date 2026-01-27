package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.exception.GlobalExceptionHandler.ErrorResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests for file upload quota and user storage quota functionality.
 *
 * This test class configures:
 * - File upload quota: 1 MB (files larger than 1MB are rejected)
 * - User quota: 2 MB (total storage per user limited to 2MB)
 *
 * Tests cover:
 * - File upload quota (single file size limit) - HTTP 413 when exceeded
 * - User storage quota (total storage per user) - HTTP 507 when exceeded
 * - Quota validation when Content-Length header is present (pre-validation)
 * - Replace content scenarios respecting quotas
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class QuotaIT extends TestContainersBaseConfig {

    // File upload quota: 1 MB
    private static final int FILE_UPLOAD_QUOTA_MB = 1;
    // User quota: 2 MB
    private static final int USER_QUOTA_MB = 2;

    // Size constants in bytes
    private static final int ONE_KB = 1024;
    private static final int ONE_MB = 1024 * 1024;
    private static final int HALF_MB = ONE_MB / 2;

    public QuotaIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @DynamicPropertySource
    static void configureQuotaProperties(DynamicPropertyRegistry registry) {
        // Set file upload quota to 1 MB
        registry.add("openfilz.quota.file-upload", () -> FILE_UPLOAD_QUOTA_MB);
        // Set user quota to 2 MB
        registry.add("openfilz.quota.user", () -> USER_QUOTA_MB);
    }

    // ============================================
    // FILE UPLOAD QUOTA TESTS (HTTP 413)
    // ============================================

    /**
     * Test that uploading a file larger than the file upload quota returns HTTP 413 PAYLOAD_TOO_LARGE.
     * No cleanup needed as upload fails.
     */
    @Test
    void whenUploadFileLargerThanFileQuota_thenPayloadTooLarge() {
        // Create a file larger than 1 MB (the configured file upload quota)
        int fileSizeExceedingQuota = ONE_MB + ONE_KB; // 1 MB + 1 KB
        MultipartBodyBuilder builder = createFileBuilder("large-file.txt", fileSizeExceedingQuota);

        ErrorResponse errorResponse = getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
                .expectBody(ErrorResponse.class)
                .returnResult().getResponseBody();

        assertNotNull(errorResponse);
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE.value(), errorResponse.status());
        assertTrue(errorResponse.message().contains("exceeds the maximum allowed size"));
    }

    /**
     * Test that uploading a file within the file upload quota succeeds.
     */
    @Test
    void whenUploadFileWithinFileQuota_thenSuccess() {
        List<UUID> uploadedIds = new ArrayList<>();
        try {
            // Create a file smaller than 1 MB
            int fileSizeWithinQuota = HALF_MB; // 512 KB
            MultipartBodyBuilder builder = createFileBuilder("small-file.txt", fileSizeWithinQuota);

            UploadResponse response = getWebTestClient().post()
                    .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                            .queryParam("allowDuplicateFileNames", true)
                            .build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody(UploadResponse.class)
                    .returnResult().getResponseBody();

            assertNotNull(response);
            assertNotNull(response.id());
            uploadedIds.add(response.id());
            assertEquals("small-file.txt", response.name());
            assertEquals((long) fileSizeWithinQuota, response.size());
        } finally {
            deleteFiles(uploadedIds);
        }
    }

    /**
     * Test that uploading a file exactly at the file upload quota limit succeeds.
     */
    @Test
    void whenUploadFileExactlyAtFileQuotaLimit_thenSuccess() {
        List<UUID> uploadedIds = new ArrayList<>();
        try {
            // Create a file exactly 1 MB (at the limit, not over)
            int exactQuotaSize = ONE_MB;
            MultipartBodyBuilder builder = createFileBuilder("exact-limit-file.txt", exactQuotaSize);

            UploadResponse response = getWebTestClient().post()
                    .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                            .queryParam("allowDuplicateFileNames", true)
                            .build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody(UploadResponse.class)
                    .returnResult().getResponseBody();

            assertNotNull(response);
            assertNotNull(response.id());
            uploadedIds.add(response.id());
        } finally {
            deleteFiles(uploadedIds);
        }
    }

    // ============================================
    // USER QUOTA TESTS (HTTP 507)
    // ============================================

    /**
     * Test that uploading files exceeding the user quota returns HTTP 507 INSUFFICIENT_STORAGE.
     */
    @Test
    void whenTotalStorageExceedsUserQuota_thenInsufficientStorage() {
        List<UUID> uploadedIds = new ArrayList<>();
        try {
            // Upload first file: 900 KB
            int firstFileSize = 900 * ONE_KB;
            MultipartBodyBuilder builder1 = createFileBuilder("quota-test-file-1.txt", firstFileSize);
            UploadResponse response1 = getWebTestClient().post()
                    .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                            .queryParam("allowDuplicateFileNames", true)
                            .build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder1.build()))
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody(UploadResponse.class)
                    .returnResult().getResponseBody();
            assertNotNull(response1);
            uploadedIds.add(response1.id());
            log.info("First file uploaded: {} bytes", firstFileSize);

            // Upload second file: 900 KB (total: 1.8 MB, still within 2 MB user quota)
            MultipartBodyBuilder builder2 = createFileBuilder("quota-test-file-2.txt", firstFileSize);
            UploadResponse response2 = getWebTestClient().post()
                    .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                            .queryParam("allowDuplicateFileNames", true)
                            .build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder2.build()))
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody(UploadResponse.class)
                    .returnResult().getResponseBody();
            assertNotNull(response2);
            uploadedIds.add(response2.id());
            log.info("Second file uploaded: {} bytes, total now ~1.8 MB", firstFileSize);

            // Upload third file: 500 KB (total would be ~2.3 MB, exceeds 2 MB user quota)
            int thirdFileSize = 500 * ONE_KB;
            MultipartBodyBuilder builder3 = createFileBuilder("quota-test-file-3.txt", thirdFileSize);
            ErrorResponse errorResponse = getWebTestClient().post()
                    .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                            .queryParam("allowDuplicateFileNames", true)
                            .build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder3.build()))
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.INSUFFICIENT_STORAGE)
                    .expectBody(ErrorResponse.class)
                    .returnResult().getResponseBody();

            assertNotNull(errorResponse);
            assertEquals(HttpStatus.INSUFFICIENT_STORAGE.value(), errorResponse.status());
            assertTrue(errorResponse.message().contains("quota exceeded"));
        } finally {
            deleteFiles(uploadedIds);
        }
    }

    /**
     * Test that uploading files within user quota succeeds.
     */
    @Test
    void whenTotalStorageWithinUserQuota_thenSuccess() {
        List<UUID> uploadedIds = new ArrayList<>();
        try {
            // Upload multiple small files that together are within 2 MB user quota
            int fileSize = 200 * ONE_KB; // 200 KB each

            for (int i = 1; i <= 5; i++) {
                MultipartBodyBuilder builder = createFileBuilder("user-quota-test-" + i + ".txt", fileSize);
                UploadResponse response = getWebTestClient().post()
                        .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                                .queryParam("allowDuplicateFileNames", true)
                                .build())
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(BodyInserters.fromMultipartData(builder.build()))
                        .exchange()
                        .expectStatus().isCreated()
                        .expectBody(UploadResponse.class)
                        .returnResult().getResponseBody();
                assertNotNull(response);
                uploadedIds.add(response.id());
                log.info("File {} uploaded, total: {} KB", i, (i * 200));
            }
            // Total: 1000 KB (1 MB), well within 2 MB quota
        } finally {
            deleteFiles(uploadedIds);
        }
    }

    // ============================================
    // REPLACE CONTENT TESTS
    // ============================================

    /**
     * Test that replacing file content with a file within quota succeeds.
     */
    @Test
    void whenReplaceFileWithinQuota_thenSuccess() {
        List<UUID> uploadedIds = new ArrayList<>();
        try {
            // Upload initial file: 500 KB
            int initialSize = HALF_MB;
            MultipartBodyBuilder uploadBuilder = createFileBuilder("replace-test.txt", initialSize);
            UploadResponse uploadResponse = getWebTestClient().post()
                    .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                            .queryParam("allowDuplicateFileNames", true)
                            .build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(uploadBuilder.build()))
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody(UploadResponse.class)
                    .returnResult().getResponseBody();
            assertNotNull(uploadResponse);
            uploadedIds.add(uploadResponse.id());

            // Replace with another file within file quota (800 KB)
            int replacementSize = 800 * ONE_KB;
            MultipartBodyBuilder replaceBuilder = createFileBuilder("replacement.txt", replacementSize);

            getWebTestClient().put()
                    .uri(RestApiVersion.API_PREFIX + "/documents/" + uploadResponse.id() + "/replace-content")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(replaceBuilder.build()))
                    .exchange()
                    .expectStatus().isOk();
        } finally {
            deleteFiles(uploadedIds);
        }
    }

    /**
     * Test that replacing file content with a file exceeding file quota returns HTTP 413.
     */
    @Test
    void whenReplaceFileExceedsFileQuota_thenPayloadTooLarge() {
        List<UUID> uploadedIds = new ArrayList<>();
        try {
            // Upload initial small file
            int initialSize = 100 * ONE_KB;
            MultipartBodyBuilder uploadBuilder = createFileBuilder("replace-quota-test.txt", initialSize);
            UploadResponse uploadResponse = getWebTestClient().post()
                    .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                            .queryParam("allowDuplicateFileNames", true)
                            .build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(uploadBuilder.build()))
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody(UploadResponse.class)
                    .returnResult().getResponseBody();
            assertNotNull(uploadResponse);
            uploadedIds.add(uploadResponse.id());

            // Try to replace with a file exceeding 1 MB file quota
            int replacementSize = ONE_MB + ONE_KB;
            MultipartBodyBuilder replaceBuilder = createFileBuilder("large-replacement.txt", replacementSize);

            ErrorResponse errorResponse = getWebTestClient().put()
                    .uri(RestApiVersion.API_PREFIX + "/documents/" + uploadResponse.id() + "/replace-content")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(replaceBuilder.build()))
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
                    .expectBody(ErrorResponse.class)
                    .returnResult().getResponseBody();

            assertNotNull(errorResponse);
            assertEquals(HttpStatus.PAYLOAD_TOO_LARGE.value(), errorResponse.status());
        } finally {
            deleteFiles(uploadedIds);
        }
    }

    // ============================================
    // COMBINED QUOTA TESTS
    // ============================================

    /**
     * Test that file quota is checked before user quota.
     * A file exceeding file quota should return 413, not 507.
     */
    @Test
    void whenFileExceedsBothQuotas_thenPayloadTooLargeReturned() {
        List<UUID> uploadedIds = new ArrayList<>();
        try {
            // Upload files to fill up user quota
            int fileSize = 900 * ONE_KB;
            MultipartBodyBuilder builder1 = createFileBuilder("fill-quota-1.txt", fileSize);
            UploadResponse response1 = getWebTestClient().post()
                    .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                            .queryParam("allowDuplicateFileNames", true)
                            .build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder1.build()))
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody(UploadResponse.class)
                    .returnResult().getResponseBody();
            assertNotNull(response1);
            uploadedIds.add(response1.id());

            // Now try to upload a file that exceeds BOTH file quota (>1MB) and would exceed user quota
            // File quota should be checked first, so we should get 413, not 507
            int largeFileSize = ONE_MB + 500 * ONE_KB; // 1.5 MB
            MultipartBodyBuilder builder2 = createFileBuilder("exceeds-both.txt", largeFileSize);

            ErrorResponse errorResponse = getWebTestClient().post()
                    .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                            .queryParam("allowDuplicateFileNames", true)
                            .build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder2.build()))
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
                    .expectBody(ErrorResponse.class)
                    .returnResult().getResponseBody();

            assertNotNull(errorResponse);
            assertEquals(HttpStatus.PAYLOAD_TOO_LARGE.value(), errorResponse.status());
        } finally {
            deleteFiles(uploadedIds);
        }
    }

    // ============================================
    // HELPER METHODS
    // ============================================

    /**
     * Creates a MultipartBodyBuilder with a file of the specified size.
     *
     * @param filename    the filename to use
     * @param sizeInBytes the size of the file content in bytes
     * @return configured MultipartBodyBuilder
     */
    private MultipartBodyBuilder createFileBuilder(String filename, int sizeInBytes) {
        byte[] content = new byte[sizeInBytes];
        // Fill with repeating pattern for debugging if needed
        for (int i = 0; i < sizeInBytes; i++) {
            content[i] = (byte) ('A' + (i % 26));
        }

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        return builder;
    }

    /**
     * Deletes the uploaded files to clean up after tests.
     *
     * @param documentIds the list of document IDs to delete
     */
    private void deleteFiles(List<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        try {
            DeleteRequest deleteRequest = new DeleteRequest(documentIds);
            getWebTestClient().method(org.springframework.http.HttpMethod.DELETE)
                    .uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FILES)
                    .body(BodyInserters.fromValue(deleteRequest))
                    .exchange()
                    .expectStatus().isNoContent();
            log.info("Cleaned up {} files", documentIds.size());
        } catch (Exception e) {
            log.warn("Failed to clean up files: {}", e.getMessage());
        }
    }
}
