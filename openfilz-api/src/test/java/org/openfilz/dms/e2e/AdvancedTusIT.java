package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.TusFinalizeRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Advanced TUS upload tests covering:
 * - TusController.createUpload() DuplicateNameException branch
 * - TusController.createUpload() UserQuotaExceededException branch (via FileQuotaIT)
 * - TusController.parseMetadataHeader() key-only (no value) branch
 * - TusController.parseMetadataHeader() allowDuplicateFileNames=true branch
 * - TusUploadServiceImpl.parseMetadataHeader() key-without-value branch (parts.length == 1)
 * - TusUploadServiceImpl.getContentType() various extensions and no-extension
 * - TusUploadServiceImpl.validateDuplicateName() with allowDuplicates=true
 * - Full TUS flow: create → upload chunk → finalize (with metadata)
 * - TUS upload with parentFolderId
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class AdvancedTusIT extends TestContainersBaseConfig {

    private static final String TUS_ENDPOINT = RestApiVersion.API_PREFIX + "/tus";

    public AdvancedTusIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    // ==================== Create upload with key-only metadata ====================

    @Test
    void whenTusCreateWithKeyOnlyMetadata_thenOk() {
        // Key without value in metadata header
        String filename = "key-only-" + UUID.randomUUID() + ".txt";
        String filenameB64 = Base64.getEncoder().encodeToString(filename.getBytes(StandardCharsets.UTF_8));
        // "keyonly" has no value part - tests parts.length == 1 branch
        String metadata = "filename " + filenameB64 + ",keyonly";

        String location = getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "10")
                .header("Upload-Metadata", metadata)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("Location")
                .returnResult(Void.class)
                .getResponseHeaders().getFirst("Location");

        Assertions.assertNotNull(location);
    }

    // ==================== Create upload with allowDuplicateFileNames ====================

    @Test
    void whenTusCreateWithAllowDuplicates_thenOk() {
        String filename = "dup-tus-" + UUID.randomUUID() + ".txt";
        String filenameB64 = Base64.getEncoder().encodeToString(filename.getBytes(StandardCharsets.UTF_8));
        String allowDupB64 = Base64.getEncoder().encodeToString("true".getBytes(StandardCharsets.UTF_8));
        String metadata = "filename " + filenameB64 + ",allowDuplicateFileNames " + allowDupB64;

        String location = getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "10")
                .header("Upload-Metadata", metadata)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("Location")
                .returnResult(Void.class)
                .getResponseHeaders().getFirst("Location");

        Assertions.assertNotNull(location);
    }

    // ==================== Create upload with parentFolderId ====================

    @Test
    void whenTusCreateWithParentFolderId_thenOk() {
        String folderName = "tus-parent-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        String filename = "tus-in-folder-" + UUID.randomUUID() + ".txt";
        String filenameB64 = Base64.getEncoder().encodeToString(filename.getBytes(StandardCharsets.UTF_8));
        String parentB64 = Base64.getEncoder().encodeToString(folder.id().toString().getBytes(StandardCharsets.UTF_8));
        String metadata = "filename " + filenameB64 + ",parentFolderId " + parentB64;

        String location = getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "10")
                .header("Upload-Metadata", metadata)
                .exchange()
                .expectStatus().isCreated()
                .returnResult(Void.class)
                .getResponseHeaders().getFirst("Location");

        Assertions.assertNotNull(location);
    }

    // ==================== Duplicate name → 409 ====================

    @Test
    void whenTusCreateWithDuplicateName_thenConflict() {
        String filename = "tus-dup-" + UUID.randomUUID() + ".txt";

        // First upload: create and finalize
        createAndFinalizeTusUpload(filename, null);

        // Second upload with same name → should fail with 409
        String filenameB64 = Base64.getEncoder().encodeToString(filename.getBytes(StandardCharsets.UTF_8));
        String metadata = "filename " + filenameB64;

        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "5")
                .header("Upload-Metadata", metadata)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    // ==================== Full TUS flow with finalize and metadata ====================

    @Test
    void whenCompleteTusFlowWithMetadata_thenDocumentCreated() {
        String filename = "tus-full-" + UUID.randomUUID() + ".txt";
        byte[] content = "Hello TUS!".getBytes(StandardCharsets.UTF_8);

        // Create upload
        String uploadId = createTusUpload(filename, content.length);

        // Upload chunk
        getWebTestClient().patch()
                .uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header("Upload-Offset", "0")
                .header("Content-Length", String.valueOf(content.length))
                .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                .bodyValue(content)
                .exchange()
                .expectStatus().isNoContent();

        // Finalize with metadata
        TusFinalizeRequest finalizeRequest = new TusFinalizeRequest(
                filename, null, Map.of("source", "tus-test"), null);

        UploadResponse response = getWebTestClient().post()
                .uri(TUS_ENDPOINT + "/{uploadId}/finalize", uploadId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(finalizeRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
        Assertions.assertEquals(filename, response.name());
    }

    // ==================== TUS with no-extension filename ====================

    @Test
    void whenTusFinalizeWithNoExtensionFilename_thenContentTypeIsOctetStream() {
        String filename = "no-ext-file-" + UUID.randomUUID();
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);

        String uploadId = createTusUpload(filename, content.length);

        // Upload chunk
        getWebTestClient().patch()
                .uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header("Upload-Offset", "0")
                .header("Content-Length", String.valueOf(content.length))
                .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                .bodyValue(content)
                .exchange()
                .expectStatus().isNoContent();

        // Finalize
        TusFinalizeRequest finalizeRequest = new TusFinalizeRequest(filename, null, null, null);

        UploadResponse response = getWebTestClient().post()
                .uri(TUS_ENDPOINT + "/{uploadId}/finalize", uploadId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(finalizeRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
        Assertions.assertEquals("application/octet-stream", response.contentType());
    }

    // ==================== TUS with various content types ====================

    @Test
    void whenTusFinalizeWithJsonExtension_thenContentTypeIsJson() {
        String filename = "data-" + UUID.randomUUID() + ".json";
        byte[] content = "{}".getBytes(StandardCharsets.UTF_8);

        String uploadId = createTusUpload(filename, content.length);

        getWebTestClient().patch()
                .uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header("Upload-Offset", "0")
                .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                .bodyValue(content)
                .exchange()
                .expectStatus().isNoContent();

        TusFinalizeRequest finalizeRequest = new TusFinalizeRequest(filename, null, null, null);

        UploadResponse response = getWebTestClient().post()
                .uri(TUS_ENDPOINT + "/{uploadId}/finalize", uploadId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(finalizeRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
        Assertions.assertEquals("application/json", response.contentType());
    }

    // ==================== TUS finalize in a folder ====================

    @Test
    void whenTusFinalizeInFolder_thenDocumentInFolder() {
        String folderName = "tus-finalize-folder-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        String filename = "tus-in-folder-" + UUID.randomUUID() + ".txt";
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);

        String uploadId = createTusUpload(filename, content.length);

        getWebTestClient().patch()
                .uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header("Upload-Offset", "0")
                .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                .bodyValue(content)
                .exchange()
                .expectStatus().isNoContent();

        TusFinalizeRequest finalizeRequest = new TusFinalizeRequest(filename, folder.id(), null, null);

        UploadResponse response = getWebTestClient().post()
                .uri(TUS_ENDPOINT + "/{uploadId}/finalize", uploadId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(finalizeRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
    }

    // ==================== TUS finalize with allowDuplicateFileNames ====================

    @Test
    void whenTusFinalizeWithAllowDuplicates_thenOk() {
        String filename = "tus-allowdup-" + UUID.randomUUID() + ".txt";
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);

        // First upload
        createAndFinalizeTusUpload(filename, null);

        // Second upload with same name - need allowDuplicateFileNames in create metadata too
        String filenameB64 = Base64.getEncoder().encodeToString(filename.getBytes(StandardCharsets.UTF_8));
        String allowDupB64 = Base64.getEncoder().encodeToString("true".getBytes(StandardCharsets.UTF_8));
        String metadata = "filename " + filenameB64 + ",allowDuplicateFileNames " + allowDupB64;

        String location = getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", String.valueOf(content.length))
                .header("Upload-Metadata", metadata)
                .exchange()
                .expectStatus().isCreated()
                .returnResult(Void.class)
                .getResponseHeaders().getFirst("Location");

        String uploadId = location.substring(location.lastIndexOf("/") + 1);

        getWebTestClient().patch()
                .uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header("Upload-Offset", "0")
                .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                .bodyValue(content)
                .exchange()
                .expectStatus().isNoContent();

        TusFinalizeRequest finalizeRequest = new TusFinalizeRequest(filename, null, null, true);

        getWebTestClient().post()
                .uri(TUS_ENDPOINT + "/{uploadId}/finalize", uploadId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(finalizeRequest))
                .exchange()
                .expectStatus().isCreated();
    }

    // ==================== TUS with blank parentFolderId in metadata ====================

    @Test
    void whenTusCreateWithBlankParentFolderId_thenIgnored() {
        String filename = "tus-blank-parent-" + UUID.randomUUID() + ".txt";
        String filenameB64 = Base64.getEncoder().encodeToString(filename.getBytes(StandardCharsets.UTF_8));
        // Blank value for parentFolderId
        String parentB64 = Base64.getEncoder().encodeToString("".getBytes(StandardCharsets.UTF_8));
        String metadata = "filename " + filenameB64 + ",parentFolderId " + parentB64;

        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "10")
                .header("Upload-Metadata", metadata)
                .exchange()
                .expectStatus().isCreated();
    }

    // ==================== Helper Methods ====================

    private String createTusUpload(String filename, long length) {
        String filenameB64 = Base64.getEncoder().encodeToString(filename.getBytes(StandardCharsets.UTF_8));
        String metadata = "filename " + filenameB64;

        String location = getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", String.valueOf(length))
                .header("Upload-Metadata", metadata)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("Location")
                .returnResult(Void.class)
                .getResponseHeaders().getFirst("Location");

        return location.substring(location.lastIndexOf("/") + 1);
    }

    private void createAndFinalizeTusUpload(String filename, UUID parentFolderId) {
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);
        String uploadId = createTusUpload(filename, content.length);

        getWebTestClient().patch()
                .uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header("Upload-Offset", "0")
                .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                .bodyValue(content)
                .exchange()
                .expectStatus().isNoContent();

        TusFinalizeRequest finalizeRequest = new TusFinalizeRequest(filename, parentFolderId, null, null);
        getWebTestClient().post()
                .uri(TUS_ENDPOINT + "/{uploadId}/finalize", uploadId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(finalizeRequest))
                .exchange()
                .expectStatus().isCreated();
    }

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
}
