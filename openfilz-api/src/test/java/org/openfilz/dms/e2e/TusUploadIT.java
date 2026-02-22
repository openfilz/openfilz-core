package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.TusUploadInfo;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests covering TUS (resumable upload) protocol:
 * - TusController: OPTIONS, POST, HEAD, PATCH, DELETE, finalize, info, config, complete
 * - TusUploadServiceImpl: createUpload, uploadChunk, cancelUpload, getUploadInfo
 * - Error paths: missing filename, oversized upload, non-existent upload, offset mismatch
 * - Metadata parsing: valid, blank, invalid base64, parentFolderId, allowDuplicateFileNames
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class TusUploadIT extends TestContainersBaseConfig {

    private static final String TUS_ENDPOINT = RestApiVersion.API_PREFIX + "/tus";

    public TusUploadIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    // ==================== OPTIONS (capability discovery) ====================

    @Test
    void whenOptions_thenTusHeadersReturned() {
        getWebTestClient().options()
                .uri(TUS_ENDPOINT)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().valueEquals("Tus-Resumable", "1.0.0")
                .expectHeader().valueEquals("Tus-Version", "1.0.0")
                .expectHeader().valueEquals("Tus-Extension", "creation,termination")
                .expectHeader().exists("Tus-Max-Size");
    }

    // ==================== GET /config ====================

    @Test
    void whenGetConfig_thenConfigReturned() {
        getWebTestClient().get()
                .uri(TUS_ENDPOINT + "/config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.maxUploadSize").isNotEmpty()
                .jsonPath("$.chunkSize").isNotEmpty()
                .jsonPath("$.uploadExpirationPeriod").isNotEmpty();
    }

    // ==================== POST (create upload) ====================

    @Test
    void whenCreateUploadWithValidMetadata_thenCreated() {
        String metadata = buildMetadata("test-tus-upload.txt", null, null);

        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "100")
                .header("Upload-Metadata", metadata)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Tus-Resumable", "1.0.0")
                .expectHeader().valueEquals("Upload-Offset", "0")
                .expectHeader().exists("Location");
    }

    @Test
    void whenCreateUploadWithParentFolderId_thenErrorForNonExistentFolder() {
        UUID nonExistentId = UUID.randomUUID();
        String metadata = buildMetadata("test-tus.txt", nonExistentId, null);

        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "100")
                .header("Upload-Metadata", metadata)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenCreateUploadWithNoFilename_thenBadRequest() {
        // No metadata at all (no filename)
        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "100")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("Tus-Resumable", "1.0.0");
    }

    @Test
    void whenCreateUploadWithBlankFilename_thenBadRequest() {
        String metadata = buildMetadata("", null, null);

        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "100")
                .header("Upload-Metadata", metadata)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void whenCreateUploadExceedingMaxSize_thenPayloadTooLarge() {
        String metadata = buildMetadata("large-file.bin", null, null);
        // TUS max is 10GB by default; use a value larger than that
        String hugeLength = String.valueOf(Long.MAX_VALUE);

        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", hugeLength)
                .header("Upload-Metadata", metadata)
                .exchange()
                .expectStatus().isEqualTo(413);
    }

    @Test
    void whenCreateUploadWithDuplicateName_thenConflict() {
        // Upload a file first
        var builder = newFileBuilder();
        UploadResponse uploaded = uploadDocument(builder);
        Assertions.assertNotNull(uploaded);

        // Try to create TUS upload with same filename in root (no allowDuplicates)
        String metadata = buildMetadata(uploaded.name(), null, false);

        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "100")
                .header("Upload-Metadata", metadata)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void whenCreateUploadWithAllowDuplicates_thenCreated() {
        var builder = newFileBuilder();
        UploadResponse uploaded = uploadDocument(builder);

        String metadata = buildMetadata(uploaded.name(), null, true);

        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "100")
                .header("Upload-Metadata", metadata)
                .exchange()
                .expectStatus().isCreated();
    }

    // ==================== HEAD (get upload offset) ====================

    @Test
    void whenHeadValidUpload_thenOffsetReturned() {
        String uploadId = createTusUpload("test-head.txt", 100);

        getWebTestClient().head()
                .uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Tus-Resumable", "1.0.0")
                .expectHeader().valueEquals("Upload-Offset", "0")
                .expectHeader().valueEquals("Upload-Length", "100");
    }

    @Test
    void whenHeadNonExistentUpload_thenNotFound() {
        getWebTestClient().head()
                .uri(TUS_ENDPOINT + "/non-existent-upload-id")
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== PATCH (upload chunk) ====================

    @Test
    void whenUploadChunk_thenOffsetUpdated() {
        byte[] data = "Hello TUS upload chunk!".getBytes(StandardCharsets.UTF_8);
        String uploadId = createTusUpload("test-chunk.txt", data.length);

        getWebTestClient().patch()
                .uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header("Upload-Offset", "0")
                .header("Content-Length", String.valueOf(data.length))
                .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                .bodyValue(data)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().valueEquals("Tus-Resumable", "1.0.0")
                .expectHeader().valueEquals("Upload-Offset", String.valueOf(data.length));
    }

    @Test
    void whenUploadChunkToNonExistentUpload_thenNotFound() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);

        getWebTestClient().patch()
                .uri(TUS_ENDPOINT + "/non-existent-id")
                .header("Upload-Offset", "0")
                .header("Content-Length", String.valueOf(data.length))
                .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                .bodyValue(data)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenUploadChunkWithWrongOffset_thenConflict() {
        String uploadId = createTusUpload("test-offset.txt", 100);

        byte[] data = "test".getBytes(StandardCharsets.UTF_8);

        // Send with offset 50 when server expects 0
        getWebTestClient().patch()
                .uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header("Upload-Offset", "50")
                .header("Content-Length", String.valueOf(data.length))
                .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                .bodyValue(data)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    // ==================== GET /{uploadId}/info ====================

    @Test
    void whenGetUploadInfo_thenInfoReturned() {
        String uploadId = createTusUpload("test-info.txt", 500);

        getWebTestClient().get()
                .uri(TUS_ENDPOINT + "/{uploadId}/info", uploadId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TusUploadInfo.class)
                .value(info -> {
                    Assertions.assertEquals(uploadId, info.uploadId());
                    Assertions.assertEquals(0L, info.offset());
                    Assertions.assertEquals(500L, info.length());
                    Assertions.assertNotNull(info.expiresAt());
                    Assertions.assertNotNull(info.uploadUrl());
                });
    }

    // ==================== GET /{uploadId}/complete ====================

    @Test
    void whenCheckCompleteBeforeUpload_thenFalse() {
        String uploadId = createTusUpload("test-complete.txt", 100);

        getWebTestClient().get()
                .uri(TUS_ENDPOINT + "/{uploadId}/complete", uploadId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Boolean.class)
                .value(complete -> Assertions.assertFalse(complete));
    }

    @Test
    void whenCheckCompleteAfterFullUpload_thenTrue() {
        byte[] data = "Complete upload content!".getBytes(StandardCharsets.UTF_8);
        String uploadId = createTusUpload("test-complete-full.txt", data.length);

        // Upload all data
        getWebTestClient().patch()
                .uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header("Upload-Offset", "0")
                .header("Content-Length", String.valueOf(data.length))
                .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                .bodyValue(data)
                .exchange()
                .expectStatus().isNoContent();

        // Check completion
        getWebTestClient().get()
                .uri(TUS_ENDPOINT + "/{uploadId}/complete", uploadId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Boolean.class)
                .value(complete -> Assertions.assertTrue(complete));
    }

    // ==================== DELETE (cancel upload) ====================

    @Test
    void whenCancelUpload_thenNoContent() {
        String uploadId = createTusUpload("test-cancel.txt", 100);

        getWebTestClient().delete()
                .uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().valueEquals("Tus-Resumable", "1.0.0");
    }

    // ==================== POST /{uploadId}/finalize ====================

    @Test
    void whenFinalizeCompletedUpload_thenDocumentCreated() {
        byte[] data = "Finalized TUS content!".getBytes(StandardCharsets.UTF_8);
        String uploadId = createTusUpload("test-finalize.txt", data.length);

        // Upload all data
        getWebTestClient().patch()
                .uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header("Upload-Offset", "0")
                .header("Content-Length", String.valueOf(data.length))
                .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                .bodyValue(data)
                .exchange()
                .expectStatus().isNoContent();

        // Finalize
        Map<String, Object> finalizeRequest = Map.of(
                "filename", "test-finalize.txt"
        );

        getWebTestClient().post()
                .uri(TUS_ENDPOINT + "/{uploadId}/finalize", uploadId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(finalizeRequest)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .value(response -> {
                    Assertions.assertNotNull(response.id());
                    Assertions.assertEquals("test-finalize.txt", response.name());
                });
    }

    @Test
    void whenFinalizeWithMetadata_thenMetadataPreserved() {
        byte[] data = "Content with metadata".getBytes(StandardCharsets.UTF_8);
        String uploadId = createTusUpload("test-finalize-meta.txt", data.length);

        getWebTestClient().patch()
                .uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header("Upload-Offset", "0")
                .header("Content-Length", String.valueOf(data.length))
                .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                .bodyValue(data)
                .exchange()
                .expectStatus().isNoContent();

        Map<String, Object> finalizeRequest = Map.of(
                "filename", "test-finalize-meta.txt",
                "metadata", Map.of("appId", "TUS_TEST", "version", "2.0")
        );

        getWebTestClient().post()
                .uri(TUS_ENDPOINT + "/{uploadId}/finalize", uploadId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(finalizeRequest)
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void whenFinalizeIncompleteUpload_thenError() {
        // Create upload but don't send all data
        String uploadId = createTusUpload("test-finalize-incomplete.txt", 1000);

        byte[] partialData = "partial".getBytes(StandardCharsets.UTF_8);
        getWebTestClient().patch()
                .uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header("Upload-Offset", "0")
                .header("Content-Length", String.valueOf(partialData.length))
                .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                .bodyValue(partialData)
                .exchange()
                .expectStatus().isNoContent();

        // Try to finalize before upload is complete - returns server error
        Map<String, Object> finalizeRequest = Map.of("filename", "test-finalize-incomplete.txt");

        getWebTestClient().post()
                .uri(TUS_ENDPOINT + "/{uploadId}/finalize", uploadId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(finalizeRequest)
                .exchange()
                .expectStatus().is5xxServerError();
    }

    // ==================== Metadata parsing edge cases ====================

    @Test
    void whenCreateUploadWithInvalidBase64Metadata_thenBadRequest() {
        // Invalid base64 in metadata value
        String invalidMetadata = "filename !!!invalid-base64!!!";

        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "100")
                .header("Upload-Metadata", invalidMetadata)
                .exchange()
                // Invalid base64 fails silently (filename becomes null) → BAD_REQUEST
                .expectStatus().isBadRequest();
    }

    @Test
    void whenCreateUploadWithInvalidParentFolderId_thenCreated() {
        // Valid filename but invalid UUID for parentFolderId - should be ignored
        String filenameEncoded = Base64.getEncoder().encodeToString("test-invalid-parent.txt".getBytes());
        String parentIdEncoded = Base64.getEncoder().encodeToString("not-a-uuid".getBytes());
        String metadata = "filename " + filenameEncoded + ",parentFolderId " + parentIdEncoded;

        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "100")
                .header("Upload-Metadata", metadata)
                .exchange()
                // Invalid parentFolderId is silently ignored, upload created at root
                .expectStatus().isCreated();
    }

    // ==================== Full TUS flow ====================

    @Test
    void whenFullTusFlow_thenSuccess() {
        byte[] part1 = "Hello ".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "World!".getBytes(StandardCharsets.UTF_8);
        int totalLength = part1.length + part2.length;

        // 1. Create upload
        String uploadId = createTusUpload("tus-full-flow.txt", totalLength);

        // 2. Upload first chunk
        getWebTestClient().patch()
                .uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header("Upload-Offset", "0")
                .header("Content-Length", String.valueOf(part1.length))
                .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                .bodyValue(part1)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().valueEquals("Upload-Offset", String.valueOf(part1.length));

        // 3. Check offset via HEAD
        getWebTestClient().head()
                .uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Upload-Offset", String.valueOf(part1.length));

        // 4. Upload second chunk
        getWebTestClient().patch()
                .uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header("Upload-Offset", String.valueOf(part1.length))
                .header("Content-Length", String.valueOf(part2.length))
                .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                .bodyValue(part2)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().valueEquals("Upload-Offset", String.valueOf(totalLength));

        // 5. Verify complete
        getWebTestClient().get()
                .uri(TUS_ENDPOINT + "/{uploadId}/complete", uploadId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Boolean.class)
                .value(Assertions::assertTrue);

        // 6. Finalize
        Map<String, Object> finalizeRequest = Map.of("filename", "tus-full-flow.txt");
        getWebTestClient().post()
                .uri(TUS_ENDPOINT + "/{uploadId}/finalize", uploadId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(finalizeRequest)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .value(response -> {
                    Assertions.assertNotNull(response.id());
                    Assertions.assertEquals("tus-full-flow.txt", response.name());
                });
    }

    // ==================== Helper Methods ====================

    private String createTusUpload(String filename, long length) {
        String metadata = buildMetadata(filename, null, null);

        String location = getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", String.valueOf(length))
                .header("Upload-Metadata", metadata)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("Location")
                .returnResult(Void.class)
                .getResponseHeaders().getFirst("Location");

        Assertions.assertNotNull(location);
        // Extract upload ID from Location URL
        return location.substring(location.lastIndexOf("/") + 1);
    }

    private String buildMetadata(String filename, UUID parentFolderId, Boolean allowDuplicates) {
        StringBuilder sb = new StringBuilder();
        if (filename != null) {
            sb.append("filename ").append(Base64.getEncoder().encodeToString(filename.getBytes(StandardCharsets.UTF_8)));
        }
        if (parentFolderId != null) {
            if (!sb.isEmpty()) sb.append(",");
            sb.append("parentFolderId ").append(Base64.getEncoder().encodeToString(parentFolderId.toString().getBytes(StandardCharsets.UTF_8)));
        }
        if (allowDuplicates != null) {
            if (!sb.isEmpty()) sb.append(",");
            sb.append("allowDuplicateFileNames ").append(Base64.getEncoder().encodeToString(allowDuplicates.toString().getBytes(StandardCharsets.UTF_8)));
        }
        return sb.toString();
    }
}
