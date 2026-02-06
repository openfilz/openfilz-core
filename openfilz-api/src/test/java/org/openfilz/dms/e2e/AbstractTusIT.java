package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.controller.rest.TusController;
import org.openfilz.dms.dto.request.TusFinalizeRequest;
import org.openfilz.dms.dto.response.TusUploadInfo;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.scheduler.TusUploadCleanupScheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract base class for TUS (resumable upload) protocol e2e tests.
 * Tests all endpoints of {@link TusController} and {@link TusUploadCleanupScheduler}.
 *
 * Generates large files (>100MB) at startup for realistic upload testing,
 * and deletes them after all tests complete.
 *
 * Subclasses provide storage-specific configuration (local filesystem or MinIO).
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
//@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractTusIT extends TestContainersKeyCloakConfig {

    protected static final String TUS_ENDPOINT = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_TUS;
    protected static final long LARGE_FILE_SIZE = 105 * 1024 * 1024L; // 105 MB
    protected static final long SMALL_FILE_SIZE = 1024L; // 1 KB
    protected static final int CHUNK_SIZE = 10 * 1024 * 1024; // 10 MB chunks for tests

    private static Path largeFilePath;
    private static Path smallFilePath;
    private static byte[] largeFileBytes;
    private static byte[] smallFileBytes;

    protected String accessToken;

    @Autowired
    private TusUploadCleanupScheduler tusUploadCleanupScheduler;

    public AbstractTusIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @BeforeAll
    static void generateTestFiles() throws IOException {
        log.info("Generating large test files for TUS e2e tests...");

        // Generate a large file (>100MB) with random data
        largeFilePath = Files.createTempFile("tus-test-large-", ".bin");
        largeFileBytes = new byte[(int) LARGE_FILE_SIZE];
        new Random(42).nextBytes(largeFileBytes); // Seeded for reproducibility
        Files.write(largeFilePath, largeFileBytes);
        log.info("Generated large test file: {} ({} bytes)", largeFilePath, LARGE_FILE_SIZE);

        // Generate a small file (1KB)
        smallFilePath = Files.createTempFile("tus-test-small-", ".bin");
        smallFileBytes = new byte[(int) SMALL_FILE_SIZE];
        new Random(123).nextBytes(smallFileBytes);
        Files.write(smallFilePath, smallFileBytes);
        log.info("Generated small test file: {} ({} bytes)", smallFilePath, SMALL_FILE_SIZE);
    }

    @AfterAll
    static void cleanupTestFiles() throws IOException {
        if (largeFilePath != null) {
            Files.deleteIfExists(largeFilePath);
            log.info("Deleted large test file: {}", largeFilePath);
        }
        if (smallFilePath != null) {
            Files.deleteIfExists(smallFilePath);
            log.info("Deleted small test file: {}", smallFilePath);
        }
    }

    @BeforeEach
    void setupAuth() {
        accessToken = getAccessToken("contributor-user");
    }

    // =====================================================================
    // Helper methods
    // =====================================================================

    private String buildUploadMetadata(String filename, UUID parentFolderId, Boolean allowDuplicates) {
        List<String> parts = new ArrayList<>();
        if (filename != null) {
            parts.add("filename " + Base64.getEncoder().encodeToString(filename.getBytes()));
        }
        if (parentFolderId != null) {
            parts.add("parentFolderId " + Base64.getEncoder().encodeToString(parentFolderId.toString().getBytes()));
        }
        if (allowDuplicates != null) {
            parts.add("allowDuplicateFileNames " + Base64.getEncoder().encodeToString(allowDuplicates.toString().getBytes()));
        }
        return String.join(",", parts);
    }

    private WebTestClient.ResponseSpec createUpload(long uploadLength, String metadata) {
        return getWebTestClient().post().uri(TUS_ENDPOINT)
                .header("Upload-Length", String.valueOf(uploadLength))
                .header("Upload-Metadata", metadata)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
    }

    private String createUploadAndGetId(long uploadLength, String filename) {
        String metadata = buildUploadMetadata(filename, null, true);
        String location = createUpload(uploadLength, metadata)
                .expectStatus().isCreated()
                .expectHeader().exists("Location")
                .expectHeader().valueEquals("Tus-Resumable", "1.0.0")
                .returnResult(Void.class)
                .getResponseHeaders()
                .getFirst("Location");
        assertThat(location).isNotNull();
        // Extract uploadId from location URL (last path segment)
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private WebTestClient.ResponseSpec uploadChunk(String uploadId, long offset, byte[] data) {
        return getWebTestClient().patch().uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header("Upload-Offset", String.valueOf(offset))
                .header("Content-Type", "application/offset+octet-stream")
                .header("Content-Length", String.valueOf(data.length))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .bodyValue(data)
                .exchange();
    }

    private void uploadAllChunks(String uploadId, byte[] fileData) {
        long offset = 0;
        while (offset < fileData.length) {
            int end = (int) Math.min(offset + CHUNK_SIZE, fileData.length);
            byte[] chunk = Arrays.copyOfRange(fileData, (int) offset, end);
            long newOffset = offset + chunk.length;

            uploadChunk(uploadId, offset, chunk)
                    .expectStatus().isNoContent()
                    .expectHeader().valueEquals("Upload-Offset", String.valueOf(newOffset))
                    .expectHeader().valueEquals("Tus-Resumable", "1.0.0");

            offset = newOffset;
        }
    }

    // =====================================================================
    // OPTIONS /api/v1/tus - TUS capability discovery
    // =====================================================================

    @Test
    @Order(1)
    void options_shouldReturnTusCapabilities() {
        getWebTestClient().options().uri(TUS_ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().valueEquals("Tus-Resumable", "1.0.0")
                .expectHeader().valueEquals("Tus-Version", "1.0.0")
                .expectHeader().valueEquals("Tus-Extension", "creation,termination")
                .expectHeader().exists("Tus-Max-Size");
    }

    // =====================================================================
    // GET /api/v1/tus/config - TUS configuration
    // =====================================================================

    @Test
    @Order(2)
    void getConfig_shouldReturnTusConfiguration() {
        getWebTestClient().get().uri(TUS_ENDPOINT + "/config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TusController.TusConfigResponse.class)
                .value(config -> {
                    assertThat(config.enabled()).isTrue();
                    assertThat(config.maxUploadSize()).isGreaterThan(0);
                    assertThat(config.chunkSize()).isGreaterThan(0);
                    assertThat(config.uploadExpirationPeriod()).isGreaterThan(0);
                    assertThat(config.endpoint()).contains("/api/v1/tus");
                });
    }

    // =====================================================================
    // POST /api/v1/tus - Create new upload
    // =====================================================================

    @Test
    @Order(10)
    void createUpload_shouldSucceedWithValidMetadata() {
        String metadata = buildUploadMetadata("test-large-file.bin", null, true);
        createUpload(LARGE_FILE_SIZE, metadata)
                .expectStatus().isCreated()
                .expectHeader().exists("Location")
                .expectHeader().valueEquals("Tus-Resumable", "1.0.0")
                .expectHeader().valueEquals("Upload-Offset", "0");
    }

    @Test
    @Order(11)
    void createUpload_shouldReturnBadRequest_whenNoFilename() {
        // Upload-Metadata without filename
        createUpload(SMALL_FILE_SIZE, "")
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("Tus-Resumable", "1.0.0");
    }

    @Test
    @Order(12)
    void createUpload_shouldReturnPayloadTooLarge_whenExceedsMaxSize() {
        long tooLarge = 11L * 1024 * 1024 * 1024; // 11GB, exceeds default 10GB max
        String metadata = buildUploadMetadata("huge-file.bin", null, true);
        createUpload(tooLarge, metadata)
                .expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
                .expectHeader().valueEquals("Tus-Resumable", "1.0.0");
    }

    @Test
    @Order(13)
    void createUpload_shouldReturnNotFound_whenParentFolderDoesNotExist() {
        UUID nonExistentFolderId = UUID.randomUUID();
        String metadata = buildUploadMetadata("test-file.bin", nonExistentFolderId, true);
        createUpload(SMALL_FILE_SIZE, metadata)
                .expectStatus().isNotFound()
                .expectHeader().valueEquals("Tus-Resumable", "1.0.0");
    }

    // =====================================================================
    // HEAD /api/v1/tus/{uploadId} - Get upload progress
    // =====================================================================

    @Test
    @Order(20)
    void getUploadOffset_shouldReturnZero_forNewUpload() {
        String uploadId = createUploadAndGetId(SMALL_FILE_SIZE, "head-test.bin");

        getWebTestClient().head().uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Upload-Offset", "0")
                .expectHeader().valueEquals("Upload-Length", String.valueOf(SMALL_FILE_SIZE))
                .expectHeader().valueEquals("Tus-Resumable", "1.0.0")
                .expectHeader().valueEquals("Cache-Control", "no-store");
    }

    @Test
    @Order(21)
    void getUploadOffset_shouldReturnNotFound_forNonExistentUpload() {
        getWebTestClient().head().uri(TUS_ENDPOINT + "/{uploadId}", UUID.randomUUID().toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus().isNotFound();
    }

    // =====================================================================
    // PATCH /api/v1/tus/{uploadId} - Upload chunk
    // =====================================================================

    @Test
    @Order(30)
    void uploadChunk_shouldSucceed_forSmallFile() {
        String uploadId = createUploadAndGetId(SMALL_FILE_SIZE, "chunk-test-small.bin");

        uploadChunk(uploadId, 0, smallFileBytes)
                .expectStatus().isNoContent()
                .expectHeader().valueEquals("Upload-Offset", String.valueOf(SMALL_FILE_SIZE))
                .expectHeader().valueEquals("Tus-Resumable", "1.0.0");
    }

    @Test
    @Order(31)
    void uploadChunk_shouldSucceed_forLargeFileInMultipleChunks() {
        String uploadId = createUploadAndGetId(LARGE_FILE_SIZE, "chunk-test-large.bin");

        uploadAllChunks(uploadId, largeFileBytes);

        // Verify HEAD returns complete offset
        getWebTestClient().head().uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Upload-Offset", String.valueOf(LARGE_FILE_SIZE))
                .expectHeader().valueEquals("Upload-Length", String.valueOf(LARGE_FILE_SIZE));
    }

    @Test
    @Order(32)
    void uploadChunk_shouldReturnConflict_whenOffsetMismatch() {
        String uploadId = createUploadAndGetId(SMALL_FILE_SIZE, "offset-mismatch.bin");

        // Try to upload at wrong offset
        uploadChunk(uploadId, 500, smallFileBytes)
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectHeader().valueEquals("Tus-Resumable", "1.0.0");
    }

    @Test
    @Order(33)
    void uploadChunk_shouldReturnNotFound_forNonExistentUpload() {
        byte[] data = new byte[100];
        uploadChunk(UUID.randomUUID().toString(), 0, data)
                .expectStatus().isNotFound();
    }

    @Test
    @Order(34)
    void uploadChunk_resumeAfterPartialUpload() {
        String uploadId = createUploadAndGetId(SMALL_FILE_SIZE, "resume-test.bin");

        // Upload first half
        int half = (int) (SMALL_FILE_SIZE / 2);
        byte[] firstHalf = Arrays.copyOfRange(smallFileBytes, 0, half);
        uploadChunk(uploadId, 0, firstHalf)
                .expectStatus().isNoContent()
                .expectHeader().valueEquals("Upload-Offset", String.valueOf(half));

        // Verify offset via HEAD
        getWebTestClient().head().uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Upload-Offset", String.valueOf(half));

        // Resume with second half
        byte[] secondHalf = Arrays.copyOfRange(smallFileBytes, half, (int) SMALL_FILE_SIZE);
        uploadChunk(uploadId, half, secondHalf)
                .expectStatus().isNoContent()
                .expectHeader().valueEquals("Upload-Offset", String.valueOf(SMALL_FILE_SIZE));
    }

    // =====================================================================
    // GET /api/v1/tus/{uploadId}/info - Get upload info
    // =====================================================================

    @Test
    @Order(40)
    void getUploadInfo_shouldReturnUploadDetails() {
        String uploadId = createUploadAndGetId(SMALL_FILE_SIZE, "info-test.bin");

        getWebTestClient().get().uri(TUS_ENDPOINT + "/{uploadId}/info", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TusUploadInfo.class)
                .value(info -> {
                    assertThat(info.uploadId()).isEqualTo(uploadId);
                    assertThat(info.offset()).isEqualTo(0L);
                    assertThat(info.length()).isEqualTo(SMALL_FILE_SIZE);
                    assertThat(info.expiresAt()).isNotNull();
                    assertThat(info.uploadUrl()).contains(uploadId);
                });
    }

    @Test
    @Order(41)
    void getUploadInfo_shouldReflectProgress_afterPartialUpload() {
        String uploadId = createUploadAndGetId(SMALL_FILE_SIZE, "info-progress.bin");

        int half = (int) (SMALL_FILE_SIZE / 2);
        byte[] firstHalf = Arrays.copyOfRange(smallFileBytes, 0, half);
        uploadChunk(uploadId, 0, firstHalf)
                .expectStatus().isNoContent();

        getWebTestClient().get().uri(TUS_ENDPOINT + "/{uploadId}/info", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TusUploadInfo.class)
                .value(info -> {
                    assertThat(info.offset()).isEqualTo((long) half);
                    assertThat(info.length()).isEqualTo(SMALL_FILE_SIZE);
                });
    }

    // =====================================================================
    // GET /api/v1/tus/{uploadId}/complete - Check upload completion
    // =====================================================================

    @Test
    @Order(50)
    void isUploadComplete_shouldReturnFalse_forNewUpload() {
        String uploadId = createUploadAndGetId(SMALL_FILE_SIZE, "complete-check-new.bin");

        getWebTestClient().get().uri(TUS_ENDPOINT + "/{uploadId}/complete", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Boolean.class)
                .value(complete -> assertThat(complete).isFalse());
    }

    @Test
    @Order(51)
    void isUploadComplete_shouldReturnTrue_afterAllChunksUploaded() {
        String uploadId = createUploadAndGetId(SMALL_FILE_SIZE, "complete-check-done.bin");

        uploadChunk(uploadId, 0, smallFileBytes)
                .expectStatus().isNoContent();

        getWebTestClient().get().uri(TUS_ENDPOINT + "/{uploadId}/complete", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Boolean.class)
                .value(complete -> assertThat(complete).isTrue());
    }

    // =====================================================================
    // POST /api/v1/tus/{uploadId}/finalize - Finalize upload
    // =====================================================================

    @Test
    @Order(60)
    void finalizeUpload_shouldCreateDocument_forSmallFile() {
        String filename = "finalize-small-" + UUID.randomUUID() + ".bin";
        String uploadId = createUploadAndGetId(SMALL_FILE_SIZE, filename);

        // Upload all data
        uploadChunk(uploadId, 0, smallFileBytes)
                .expectStatus().isNoContent();

        // Finalize
        TusFinalizeRequest request = new TusFinalizeRequest(filename, null, null, true);
        getWebTestClient().post().uri(TUS_ENDPOINT + "/{uploadId}/finalize", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .value(response -> {
                    assertThat(response.id()).isNotNull();
                    assertThat(response.name()).isEqualTo(filename);
                    assertThat(response.size()).isEqualTo(SMALL_FILE_SIZE);
                    assertThat(response.isError()).isFalse();
                });
    }

    @Test
    @Order(61)
    void finalizeUpload_shouldCreateDocument_forLargeFile() {
        String filename = "finalize-large-" + UUID.randomUUID() + ".bin";
        String uploadId = createUploadAndGetId(LARGE_FILE_SIZE, filename);

        // Upload all chunks
        uploadAllChunks(uploadId, largeFileBytes);

        // Finalize
        TusFinalizeRequest request = new TusFinalizeRequest(filename, null, null, true);
        getWebTestClient().post().uri(TUS_ENDPOINT + "/{uploadId}/finalize", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .value(response -> {
                    assertThat(response.id()).isNotNull();
                    assertThat(response.name()).isEqualTo(filename);
                    assertThat(response.size()).isEqualTo(LARGE_FILE_SIZE);
                    assertThat(response.isError()).isFalse();
                });
    }

    @Test
    @Order(62)
    void finalizeUpload_shouldFail_whenUploadIncomplete() {
        String filename = "finalize-incomplete-" + UUID.randomUUID() + ".bin";
        String uploadId = createUploadAndGetId(SMALL_FILE_SIZE, filename);

        // Do NOT upload any data — try to finalize immediately
        TusFinalizeRequest request = new TusFinalizeRequest(filename, null, null, true);
        getWebTestClient().post().uri(TUS_ENDPOINT + "/{uploadId}/finalize", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .exchange()
                .expectStatus().is5xxServerError();
    }

    @Test
    @Order(63)
    void finalizeUpload_shouldFail_whenFilenameIsBlank() {
        String uploadId = createUploadAndGetId(SMALL_FILE_SIZE, "finalize-blank-name.bin");

        uploadChunk(uploadId, 0, smallFileBytes)
                .expectStatus().isNoContent();

        TusFinalizeRequest request = new TusFinalizeRequest("", null, null, true);
        getWebTestClient().post().uri(TUS_ENDPOINT + "/{uploadId}/finalize", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @Order(64)
    void finalizeUpload_shouldCreateDocument_withCustomMetadata() {
        String filename = "finalize-metadata-" + UUID.randomUUID() + ".bin";
        String uploadId = createUploadAndGetId(SMALL_FILE_SIZE, filename);

        uploadChunk(uploadId, 0, smallFileBytes)
                .expectStatus().isNoContent();

        Map<String, Object> customMetadata = Map.of("project", "openfilz", "version", "1.0");
        TusFinalizeRequest request = new TusFinalizeRequest(filename, null, customMetadata, true);
        getWebTestClient().post().uri(TUS_ENDPOINT + "/{uploadId}/finalize", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .value(response -> {
                    assertThat(response.id()).isNotNull();
                    assertThat(response.name()).isEqualTo(filename);
                });
    }

    @Test
    @Order(65)
    void finalizeUpload_shouldReturnConflict_whenDuplicateFilenameNotAllowed() {
        String filename = "finalize-duplicate-" + UUID.randomUUID() + ".bin";

        // First upload and finalize
        String uploadId1 = createUploadAndGetId(SMALL_FILE_SIZE, filename);
        uploadChunk(uploadId1, 0, smallFileBytes).expectStatus().isNoContent();
        TusFinalizeRequest request1 = new TusFinalizeRequest(filename, null, null, false);
        getWebTestClient().post().uri(TUS_ENDPOINT + "/{uploadId}/finalize", uploadId1)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .bodyValue(request1)
                .exchange()
                .expectStatus().isCreated();

        // Second upload with same filename, allowDuplicateFileNames = false
        String uploadId2 = createUploadAndGetId(SMALL_FILE_SIZE, filename);
        uploadChunk(uploadId2, 0, smallFileBytes).expectStatus().isNoContent();
        TusFinalizeRequest request2 = new TusFinalizeRequest(filename, null, null, false);
        getWebTestClient().post().uri(TUS_ENDPOINT + "/{uploadId}/finalize", uploadId2)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .bodyValue(request2)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    // =====================================================================
    // DELETE /api/v1/tus/{uploadId} - Cancel upload
    // =====================================================================

    @Test
    @Order(70)
    void cancelUpload_shouldSucceed_forExistingUpload() {
        accessToken = getAccessToken("admin-user");
        String uploadId = createUploadAndGetId(SMALL_FILE_SIZE, "cancel-test.bin");
        getWebTestClient().delete().uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().valueEquals("Tus-Resumable", "1.0.0");

        // Verify the upload is gone via HEAD
        getWebTestClient().head().uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @Order(71)
    void cancelUpload_shouldSucceed_afterPartialUpload() {
        accessToken = getAccessToken("admin-user");
        String uploadId = createUploadAndGetId(SMALL_FILE_SIZE, "cancel-partial.bin");

        // Upload part of the data
        int half = (int) (SMALL_FILE_SIZE / 2);
        byte[] firstHalf = Arrays.copyOfRange(smallFileBytes, 0, half);
        uploadChunk(uploadId, 0, firstHalf).expectStatus().isNoContent();

        // Cancel
        getWebTestClient().delete().uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus().isNoContent();

        // Verify the upload is gone
        getWebTestClient().head().uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus().isNotFound();
    }

    // =====================================================================
    // Full upload lifecycle (end-to-end)
    // =====================================================================

    @Test
    @Order(80)
    void fullLifecycle_largeFile_createUploadChunksFinalizeDownload() {
        String filename = "lifecycle-large-" + UUID.randomUUID() + ".bin";

        // 1. Create upload
        String uploadId = createUploadAndGetId(LARGE_FILE_SIZE, filename);

        // 2. Check initial state
        getWebTestClient().get().uri(TUS_ENDPOINT + "/{uploadId}/complete", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Boolean.class)
                .value(complete -> assertThat(complete).isFalse());

        // 3. Upload in chunks
        uploadAllChunks(uploadId, largeFileBytes);

        // 4. Check completion
        getWebTestClient().get().uri(TUS_ENDPOINT + "/{uploadId}/complete", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Boolean.class)
                .value(complete -> assertThat(complete).isTrue());

        // 5. Get info
        getWebTestClient().get().uri(TUS_ENDPOINT + "/{uploadId}/info", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TusUploadInfo.class)
                .value(info -> {
                    assertThat(info.offset()).isEqualTo(LARGE_FILE_SIZE);
                    assertThat(info.length()).isEqualTo(LARGE_FILE_SIZE);
                });

        // 6. Finalize
        TusFinalizeRequest request = new TusFinalizeRequest(filename, null, null, true);
        UploadResponse uploadResponse = getWebTestClient().post().uri(TUS_ENDPOINT + "/{uploadId}/finalize", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        assertThat(uploadResponse).isNotNull();
        assertThat(uploadResponse.id()).isNotNull();
        assertThat(uploadResponse.name()).isEqualTo(filename);
        assertThat(uploadResponse.size()).isEqualTo(LARGE_FILE_SIZE);

        // 7. Verify the document can be downloaded
        // Note: WebFlux streams large files using chunked transfer-encoding,
        // so Content-Length header is not set. We verify status, content type,
        // and the Content-Disposition header instead.
        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", uploadResponse.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/octet-stream")
                .expectHeader().value("Content-Disposition", value ->
                        assertThat(value).contains("attachment").contains(filename));
    }

    // =====================================================================
    // TusUploadCleanupScheduler tests
    // =====================================================================

    @Test
    @Order(90)
    void cleanupScheduler_shouldNotDeleteActiveUploads() {
        // Create a fresh upload (not expired)
        String uploadId = createUploadAndGetId(SMALL_FILE_SIZE, "cleanup-active.bin");

        // Run cleanup manually
        tusUploadCleanupScheduler.cleanupExpiredUploads();

        // Wait a moment for async cleanup to complete
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

        // The active upload should still be accessible
        getWebTestClient().head().uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Upload-Offset", "0");
    }

    @Test
    @Order(91)
    void cleanupScheduler_canBeInvokedWithoutError() {
        // Simply verify that the cleanup scheduler can run without throwing exceptions
        // (even when there are no expired uploads)
        Assertions.assertDoesNotThrow(() -> {
            tusUploadCleanupScheduler.cleanupExpiredUploads();
            // Wait for async processing
            Thread.sleep(2000);
        });
    }

    // =====================================================================
    // Security / authentication tests
    // =====================================================================

    @Test
    @Order(100)
    void allEndpoints_shouldRequireAuthentication() {
        // POST - create upload (without auth)
        getWebTestClient().post().uri(TUS_ENDPOINT)
                .header("Upload-Length", "1024")
                .header("Upload-Metadata", buildUploadMetadata("test.bin", null, true))
                .exchange()
                .expectStatus().isUnauthorized();

        // HEAD - get offset (without auth)
        getWebTestClient().head().uri(TUS_ENDPOINT + "/{uploadId}", "fake-id")
                .exchange()
                .expectStatus().isUnauthorized();

        // PATCH - upload chunk (without auth)
        getWebTestClient().patch().uri(TUS_ENDPOINT + "/{uploadId}", "fake-id")
                .header("Upload-Offset", "0")
                .header("Content-Type", "application/offset+octet-stream")
                .bodyValue(new byte[10])
                .exchange()
                .expectStatus().isUnauthorized();

        // DELETE - cancel upload (without auth)
        getWebTestClient().delete().uri(TUS_ENDPOINT + "/{uploadId}", "fake-id")
                .exchange()
                .expectStatus().isUnauthorized();

        // GET - upload info (without auth)
        getWebTestClient().get().uri(TUS_ENDPOINT + "/{uploadId}/info", "fake-id")
                .exchange()
                .expectStatus().isUnauthorized();

        // POST - finalize (without auth)
        getWebTestClient().post().uri(TUS_ENDPOINT + "/{uploadId}/finalize", "fake-id")
                .header("Content-Type", "application/json")
                .bodyValue(new TusFinalizeRequest("test.bin", null, null, true))
                .exchange()
                .expectStatus().isUnauthorized();

        // GET - completion check (without auth)
        getWebTestClient().get().uri(TUS_ENDPOINT + "/{uploadId}/complete", "fake-id")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // =====================================================================
    // Cross-user isolation test
    // =====================================================================

    @Test
    @Order(110)
    void uploadShouldBeIsolatedPerUser() {
        // User 1 creates an upload
        String user1Token = getAccessToken("contributor-user");
        String metadata = buildUploadMetadata("user-isolation.bin", null, true);
        String location = getWebTestClient().post().uri(TUS_ENDPOINT)
                .header("Upload-Length", String.valueOf(SMALL_FILE_SIZE))
                .header("Upload-Metadata", metadata)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user1Token)
                .exchange()
                .expectStatus().isCreated()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getFirst("Location");
        assertThat(location).isNotNull();
        String uploadId = location.substring(location.lastIndexOf('/') + 1);

        // User 2 tries to access user 1's upload via HEAD
        String user2Token = getAccessToken("admin-user");
        getWebTestClient().head().uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user2Token)
                .exchange()
                // Should fail: either 403 or 404 (implementation may vary)
                .expectStatus().is4xxClientError();
    }
}
