package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.exception.GlobalExceptionHandler.ErrorResponse;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
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
import java.util.Collections;
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
 * - Restore from recycle bin quota validation - HTTP 507 when restore would exceed quota
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
        // Enable soft-delete / recycle bin for restore quota tests
        registry.add("openfilz.soft-delete.active", () -> true);
    }

    /**
     * Ensure a clean state before each test by emptying the recycle bin.
     * With soft-delete enabled, previous test cleanups move files to the bin;
     * this ensures they are permanently removed before the next test.
     */
    @BeforeEach
    void cleanUp() {
        emptyRecycleBin();
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
            cleanupTestData(uploadedIds, null);
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
            cleanupTestData(uploadedIds, null);
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
            cleanupTestData(uploadedIds, null);
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
            cleanupTestData(uploadedIds, null);
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
            cleanupTestData(uploadedIds, null);
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
            cleanupTestData(uploadedIds, null);
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
            cleanupTestData(uploadedIds, null);
        }
    }

    // ============================================
    // RESTORE QUOTA TESTS (HTTP 507)
    // ============================================

    /**
     * Test that restoring a single deleted file that would exceed user quota returns HTTP 507.
     *
     * Scenario:
     * 1. Upload file A (900 KB) - active storage: 900 KB
     * 2. Upload file B (900 KB) - active storage: 1.8 MB
     * 3. Soft-delete file B - active storage: 900 KB
     * 4. Upload file C (900 KB) - active storage: 1.8 MB
     * 5. Restore file B → would bring total to 2.7 MB, exceeding 2 MB quota → HTTP 507
     */
    @Test
    void whenRestoreFileExceedsUserQuota_thenInsufficientStorage() {
        List<UUID> uploadedIds = new ArrayList<>();
        try {
            int fileSize = 900 * ONE_KB;

            // Upload file A
            UploadResponse responseA = uploadFileWithSize("restore-quota-A.txt", fileSize);
            assertNotNull(responseA);
            uploadedIds.add(responseA.id());
            log.info("File A uploaded: {} bytes", fileSize);

            // Upload file B
            UploadResponse responseB = uploadFileWithSize("restore-quota-B.txt", fileSize);
            assertNotNull(responseB);
            uploadedIds.add(responseB.id());
            log.info("File B uploaded: {} bytes, active storage ~1.8 MB", fileSize);

            // Soft-delete file B (active storage drops back to 900 KB)
            softDeleteFiles(Collections.singletonList(responseB.id()));
            log.info("File B soft-deleted, active storage ~900 KB");

            // Upload file C (active storage back to 1.8 MB)
            UploadResponse responseC = uploadFileWithSize("restore-quota-C.txt", fileSize);
            assertNotNull(responseC);
            uploadedIds.add(responseC.id());
            log.info("File C uploaded: {} bytes, active storage ~1.8 MB", fileSize);

            // Try to restore file B → total would be ~2.7 MB, exceeds 2 MB quota
            ErrorResponse errorResponse = restoreItemsExpectError(
                    Collections.singletonList(responseB.id()), HttpStatus.INSUFFICIENT_STORAGE);

            assertNotNull(errorResponse);
            assertEquals(HttpStatus.INSUFFICIENT_STORAGE.value(), errorResponse.status());
            assertTrue(errorResponse.message().contains("quota exceeded"));
            log.info("Restore correctly rejected with HTTP 507");
        } finally {
            cleanupTestData(uploadedIds, null);
        }
    }

    /**
     * Test that restoring a deleted file within user quota succeeds.
     *
     * Scenario:
     * 1. Upload file A (500 KB) - active storage: 500 KB
     * 2. Soft-delete file A - active storage: 0
     * 3. Upload file B (500 KB) - active storage: 500 KB
     * 4. Restore file A → total would be 1 MB, within 2 MB quota → success (HTTP 204)
     */
    @Test
    void whenRestoreFileWithinUserQuota_thenSuccess() {
        List<UUID> uploadedIds = new ArrayList<>();
        try {
            // Upload file A
            UploadResponse responseA = uploadFileWithSize("restore-ok-A.txt", HALF_MB);
            assertNotNull(responseA);
            uploadedIds.add(responseA.id());

            // Soft-delete file A
            softDeleteFiles(Collections.singletonList(responseA.id()));

            // Upload file B
            UploadResponse responseB = uploadFileWithSize("restore-ok-B.txt", HALF_MB);
            assertNotNull(responseB);
            uploadedIds.add(responseB.id());

            // Restore file A → total would be 1 MB, within 2 MB quota
            restoreItems(Collections.singletonList(responseA.id()));
            log.info("File A restored successfully within quota");

            // Verify file A is accessible again
            getWebTestClient().get()
                    .uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", responseA.id())
                    .exchange()
                    .expectStatus().isOk();
        } finally {
            cleanupTestData(uploadedIds, null);
        }
    }

    /**
     * Test that restoring a deleted folder with children that would exceed user quota returns HTTP 507.
     *
     * Scenario:
     * 1. Create a folder with a file inside (800 KB)
     * 2. Soft-delete the folder (recursively deletes folder + file)
     * 3. Upload another file (900 KB) - active storage: 900 KB
     * 4. Upload another file (900 KB) - active storage: 1.8 MB
     * 5. Restore the folder → would add 800 KB, total ~2.6 MB, exceeds 2 MB quota → HTTP 507
     */
    @Test
    void whenRestoreFolderWithChildrenExceedsUserQuota_thenInsufficientStorage() {
        List<UUID> uploadedIds = new ArrayList<>();
        List<UUID> folderIds = new ArrayList<>();
        try {
            int childFileSize = 800 * ONE_KB;

            // Create a folder
            FolderResponse folder = createFolder("restore-quota-folder", null);
            folderIds.add(folder.id());

            // Upload a file inside the folder
            UploadResponse folderFile = uploadFileWithSize("restore-quota-folder-file.txt", childFileSize, folder.id());
            assertNotNull(folderFile);
            uploadedIds.add(folderFile.id());
            log.info("Folder with file ({} bytes) created", childFileSize);

            // Soft-delete the folder (recursively soft-deletes folder + child file)
            softDeleteFolders(Collections.singletonList(folder.id()));
            log.info("Folder soft-deleted, active storage: 0");

            // Upload files to fill active storage close to quota
            UploadResponse fileD = uploadFileWithSize("restore-quota-D.txt", 900 * ONE_KB);
            assertNotNull(fileD);
            uploadedIds.add(fileD.id());

            UploadResponse fileE = uploadFileWithSize("restore-quota-E.txt", 900 * ONE_KB);
            assertNotNull(fileE);
            uploadedIds.add(fileE.id());
            log.info("Active storage ~1.8 MB");

            // Try to restore the folder → child file adds 800 KB, total ~2.6 MB, exceeds 2 MB
            ErrorResponse errorResponse = restoreItemsExpectError(
                    Collections.singletonList(folder.id()), HttpStatus.INSUFFICIENT_STORAGE);

            assertNotNull(errorResponse);
            assertEquals(HttpStatus.INSUFFICIENT_STORAGE.value(), errorResponse.status());
            assertTrue(errorResponse.message().contains("quota exceeded"));
            log.info("Folder restore correctly rejected with HTTP 507");
        } finally {
            cleanupTestData(uploadedIds, folderIds);
        }
    }

    /**
     * Test that restoring multiple deleted files that together would exceed user quota returns HTTP 507.
     *
     * Scenario:
     * 1. Upload file A (500 KB) and file B (500 KB) - active storage: 1 MB
     * 2. Soft-delete both files - active storage: 0
     * 3. Upload file C (900 KB) and file D (900 KB) - active storage: 1.8 MB
     * 4. Restore both A and B → would add 1 MB, total ~2.8 MB, exceeds 2 MB quota → HTTP 507
     */
    @Test
    void whenRestoreMultipleFilesExceedsUserQuota_thenInsufficientStorage() {
        List<UUID> uploadedIds = new ArrayList<>();
        try {
            // Upload files A and B
            UploadResponse responseA = uploadFileWithSize("restore-multi-A.txt", HALF_MB);
            assertNotNull(responseA);
            uploadedIds.add(responseA.id());

            UploadResponse responseB = uploadFileWithSize("restore-multi-B.txt", HALF_MB);
            assertNotNull(responseB);
            uploadedIds.add(responseB.id());
            log.info("Files A and B uploaded, active storage ~1 MB");

            // Soft-delete both files
            softDeleteFiles(List.of(responseA.id(), responseB.id()));
            log.info("Files A and B soft-deleted, active storage: 0");

            // Upload files C and D to fill storage close to quota
            UploadResponse responseC = uploadFileWithSize("restore-multi-C.txt", 900 * ONE_KB);
            assertNotNull(responseC);
            uploadedIds.add(responseC.id());

            UploadResponse responseD = uploadFileWithSize("restore-multi-D.txt", 900 * ONE_KB);
            assertNotNull(responseD);
            uploadedIds.add(responseD.id());
            log.info("Files C and D uploaded, active storage ~1.8 MB");

            // Try to restore both A and B → would add 1 MB, total ~2.8 MB, exceeds quota
            ErrorResponse errorResponse = restoreItemsExpectError(
                    List.of(responseA.id(), responseB.id()), HttpStatus.INSUFFICIENT_STORAGE);

            assertNotNull(errorResponse);
            assertEquals(HttpStatus.INSUFFICIENT_STORAGE.value(), errorResponse.status());
            assertTrue(errorResponse.message().contains("quota exceeded"));
        } finally {
            cleanupTestData(uploadedIds, null);
        }
    }

    /**
     * Test that restoring a folder with children within user quota succeeds.
     *
     * Scenario:
     * 1. Create a folder with a file inside (200 KB)
     * 2. Soft-delete the folder
     * 3. Restore the folder → total would be 200 KB, well within 2 MB quota → success
     */
    @Test
    void whenRestoreFolderWithChildrenWithinUserQuota_thenSuccess() {
        List<UUID> uploadedIds = new ArrayList<>();
        List<UUID> folderIds = new ArrayList<>();
        try {
            // Create a folder with a file
            FolderResponse folder = createFolder("restore-folder-ok", null);
            folderIds.add(folder.id());

            UploadResponse folderFile = uploadFileWithSize("restore-folder-ok-file.txt", 200 * ONE_KB, folder.id());
            assertNotNull(folderFile);
            uploadedIds.add(folderFile.id());

            // Soft-delete the folder
            softDeleteFolders(Collections.singletonList(folder.id()));

            // Restore the folder → 200 KB, well within quota
            restoreItems(Collections.singletonList(folder.id()));
            log.info("Folder restored successfully within quota");

            // Verify folder and child file are accessible
            getWebTestClient().get()
                    .uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", folderFile.id())
                    .exchange()
                    .expectStatus().isOk();
        } finally {
            cleanupTestData(uploadedIds, folderIds);
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
     * Uploads a file with the given name and size to the root folder.
     */
    private UploadResponse uploadFileWithSize(String filename, int sizeInBytes) {
        return uploadFileWithSize(filename, sizeInBytes, null);
    }

    /**
     * Uploads a file with the given name and size, optionally into a parent folder.
     */
    private UploadResponse uploadFileWithSize(String filename, int sizeInBytes, UUID parentFolderId) {
        MultipartBodyBuilder builder = createFileBuilder(filename, sizeInBytes);
        if (parentFolderId != null) {
            builder.part("parentFolderId", parentFolderId.toString());
        }
        return getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();
    }

    /**
     * Creates a folder with the given name and optional parent.
     */
    private FolderResponse createFolder(String name, UUID parentId) {
        CreateFolderRequest request = new CreateFolderRequest(name, parentId);
        return getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();
    }

    /**
     * Soft-deletes files via the files endpoint.
     */
    private void softDeleteFiles(List<UUID> documentIds) {
        DeleteRequest deleteRequest = new DeleteRequest(documentIds);
        getWebTestClient().method(HttpMethod.DELETE)
                .uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FILES)
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();
    }

    /**
     * Soft-deletes folders via the folders endpoint.
     */
    private void softDeleteFolders(List<UUID> folderIds) {
        DeleteRequest deleteRequest = new DeleteRequest(folderIds);
        getWebTestClient().method(HttpMethod.DELETE)
                .uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FOLDERS)
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();
    }

    /**
     * Restores items from recycle bin, expecting HTTP 204 No Content.
     */
    private void restoreItems(List<UUID> documentIds) {
        DeleteRequest request = new DeleteRequest(documentIds);
        getWebTestClient().method(HttpMethod.POST)
                .uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_RECYCLE_BIN + "/restore")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isNoContent();
    }

    /**
     * Attempts to restore items from recycle bin, expecting a specific error status.
     */
    private ErrorResponse restoreItemsExpectError(List<UUID> documentIds, HttpStatus expectedStatus) {
        DeleteRequest request = new DeleteRequest(documentIds);
        return getWebTestClient().method(HttpMethod.POST)
                .uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_RECYCLE_BIN + "/restore")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectBody(ErrorResponse.class)
                .returnResult().getResponseBody();
    }

    /**
     * Comprehensive cleanup for test data. Processes each item individually so that
     * one failure does not prevent cleanup of remaining items. Ends by emptying the recycle bin.
     *
     * @param fileIds   file document IDs to clean up (may be active or already deleted)
     * @param folderIds folder document IDs to clean up (may be active or already deleted)
     */
    private void cleanupTestData(List<UUID> fileIds, List<UUID> folderIds) {
        // Soft-delete active files individually (moves them to recycle bin)
        if (fileIds != null) {
            for (UUID id : fileIds) {
                try {
                    getWebTestClient().method(HttpMethod.DELETE)
                            .uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FILES)
                            .body(BodyInserters.fromValue(new DeleteRequest(Collections.singletonList(id))))
                            .exchange();
                } catch (Exception e) {
                    log.warn("Cleanup: failed to delete file {}: {}", id, e.getMessage());
                }
            }
        }
        // Soft-delete active folders individually (moves them to recycle bin)
        if (folderIds != null) {
            for (UUID id : folderIds) {
                try {
                    getWebTestClient().method(HttpMethod.DELETE)
                            .uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FOLDERS)
                            .body(BodyInserters.fromValue(new DeleteRequest(Collections.singletonList(id))))
                            .exchange();
                } catch (Exception e) {
                    log.warn("Cleanup: failed to delete folder {}: {}", id, e.getMessage());
                }
            }
        }
        // Empty the entire recycle bin to permanently remove all soft-deleted items
        emptyRecycleBin();
    }

    /**
     * Empties the entire recycle bin, permanently deleting all items. Ignores errors.
     */
    private void emptyRecycleBin() {
        try {
            getWebTestClient().method(HttpMethod.DELETE)
                    .uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_RECYCLE_BIN + "/empty")
                    .exchange();
        } catch (Exception e) {
            log.warn("Failed to empty recycle bin: {}", e.getMessage());
        }
    }
}
