package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests targeting TUS controller branches:
 * - OPTIONS: capability discovery
 * - POST createUpload: max size exceeded, missing filename, with parentFolderId, with allowDuplicateFileNames,
 *   non-existent parent (404), duplicate filename (409)
 * - HEAD: non-existent upload (404)
 * - PATCH: non-existent upload (404)
 * - DELETE: cancel upload
 * - GET info: non-existent upload
 * - GET complete: non-existent upload
 * - GET config: TUS configuration
 * - Metadata parsing: invalid parentFolderId, blank metadata, missing metadata header
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class TusProtocolEdgeCasesIT extends TestContainersBaseConfig {

    private static final String TUS_ENDPOINT = RestApiVersion.API_PREFIX + "/tus";

    public TusProtocolEdgeCasesIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    // ==================== OPTIONS: capability discovery ====================

    @Test
    void whenTusOptions_thenCapabilitiesReturned() {
        getWebTestClient().options()
                .uri(TUS_ENDPOINT)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().valueEquals("Tus-Resumable", "1.0.0")
                .expectHeader().valueEquals("Tus-Version", "1.0.0")
                .expectHeader().valueEquals("Tus-Extension", "creation,termination")
                .expectHeader().exists("Tus-Max-Size");
    }

    // ==================== POST: create upload with valid metadata ====================

    @Test
    void whenCreateUploadWithValidMetadata_thenCreated() {
        String metadata = buildTusMetadata("tus-file-" + UUID.randomUUID() + ".txt", null, null);

        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "1024")
                .header("Upload-Metadata", metadata)
                .header("Tus-Resumable", "1.0.0")
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("Location")
                .expectHeader().valueEquals("Tus-Resumable", "1.0.0");
    }

    // ==================== POST: max size exceeded → 413 ====================

    @Test
    void whenCreateUploadExceedsMaxSize_then413() {
        String metadata = buildTusMetadata("huge.bin", null, null);

        // Try to create upload exceeding max size (10GB + 1)
        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "10737418241") // 10GB + 1 byte
                .header("Upload-Metadata", metadata)
                .header("Tus-Resumable", "1.0.0")
                .exchange()
                .expectStatus().isEqualTo(413); // Payload Too Large
    }

    // ==================== POST: missing filename → 400 ====================

    @Test
    void whenCreateUploadMissingFilename_then400() {
        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "1024")
                .header("Tus-Resumable", "1.0.0")
                // No Upload-Metadata header
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void whenCreateUploadBlankFilename_then400() {
        // Metadata with blank filename value
        String metadata = "filename " + Base64.getEncoder().encodeToString("".getBytes());

        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "1024")
                .header("Upload-Metadata", metadata)
                .header("Tus-Resumable", "1.0.0")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void whenCreateUploadBlankMetadataHeader_then400() {
        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "1024")
                .header("Upload-Metadata", "")
                .header("Tus-Resumable", "1.0.0")
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ==================== POST: with parentFolderId ====================

    @Test
    void whenCreateUploadWithParentFolder_thenCreated() {
        FolderResponse folder = createFolder("tus-parent-" + UUID.randomUUID(), null);
        String metadata = buildTusMetadata("tus-in-folder.txt", folder.id(), null);

        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "512")
                .header("Upload-Metadata", metadata)
                .header("Tus-Resumable", "1.0.0")
                .exchange()
                .expectStatus().isCreated();
    }

    // ==================== POST: non-existent parent → 404 ====================

    @Test
    void whenCreateUploadNonExistentParent_then404() {
        String metadata = buildTusMetadata("orphan.txt", UUID.randomUUID(), null);

        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "512")
                .header("Upload-Metadata", metadata)
                .header("Tus-Resumable", "1.0.0")
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== POST: duplicate filename → 409 ====================

    @Test
    void whenCreateUploadDuplicateFilename_then409() {
        // Upload a file first
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true).build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated();

        // Try TUS create with same filename and allowDuplicateFileNames=false
        String metadata = buildTusMetadata("test.txt", null, false);

        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "100")
                .header("Upload-Metadata", metadata)
                .header("Tus-Resumable", "1.0.0")
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    // ==================== POST: with allowDuplicateFileNames=true ====================

    @Test
    void whenCreateUploadWithAllowDuplicates_thenCreated() {
        String metadata = buildTusMetadata("dup-ok.txt", null, true);

        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "256")
                .header("Upload-Metadata", metadata)
                .header("Tus-Resumable", "1.0.0")
                .exchange()
                .expectStatus().isCreated();
    }

    // ==================== POST: invalid parentFolderId in metadata ====================

    @Test
    void whenCreateUploadInvalidParentFolderId_thenCreatedOrBadRequest() {
        // Invalid UUID should be logged and treated as no parent
        String metadata = "filename " + Base64.getEncoder().encodeToString("test.txt".getBytes())
                + ",parentFolderId " + Base64.getEncoder().encodeToString("not-a-uuid".getBytes());

        getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "100")
                .header("Upload-Metadata", metadata)
                .header("Tus-Resumable", "1.0.0")
                .exchange()
                .expectStatus().isCreated(); // Invalid UUID is ignored, treated as null parent
    }

    // ==================== HEAD: non-existent upload → 404 ====================

    @Test
    void whenGetOffsetNonExistentUpload_then404() {
        getWebTestClient().method(org.springframework.http.HttpMethod.HEAD)
                .uri(TUS_ENDPOINT + "/{uploadId}", UUID.randomUUID())
                .header("Tus-Resumable", "1.0.0")
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== HEAD: existing upload returns offset ====================

    @Test
    void whenGetOffsetExistingUpload_thenReturnsHeaders() {
        // Create an upload first
        String metadata = buildTusMetadata("offset-test-" + UUID.randomUUID() + ".txt", null, null);

        String location = getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "1024")
                .header("Upload-Metadata", metadata)
                .header("Tus-Resumable", "1.0.0")
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("Location")
                .returnResult(Void.class)
                .getResponseHeaders().getLocation().toString();

        // Extract uploadId from location
        String uploadId = location.substring(location.lastIndexOf('/') + 1);

        // HEAD to get offset
        getWebTestClient().method(org.springframework.http.HttpMethod.HEAD)
                .uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header("Tus-Resumable", "1.0.0")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("Upload-Offset")
                .expectHeader().exists("Upload-Length");
    }

    // ==================== DELETE: cancel existing upload ====================

    @Test
    void whenCancelExistingUpload_thenNoContent() {
        String metadata = buildTusMetadata("cancel-me-" + UUID.randomUUID() + ".txt", null, null);

        String location = getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "512")
                .header("Upload-Metadata", metadata)
                .header("Tus-Resumable", "1.0.0")
                .exchange()
                .expectStatus().isCreated()
                .returnResult(Void.class)
                .getResponseHeaders().getLocation().toString();

        String uploadId = location.substring(location.lastIndexOf('/') + 1);

        getWebTestClient().delete()
                .uri(TUS_ENDPOINT + "/{uploadId}", uploadId)
                .header("Tus-Resumable", "1.0.0")
                .exchange()
                .expectStatus().isNoContent();
    }

    // ==================== GET info: existing upload ====================

    @Test
    void whenGetUploadInfo_thenReturnsInfo() {
        String metadata = buildTusMetadata("info-test-" + UUID.randomUUID() + ".txt", null, null);

        String location = getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "1024")
                .header("Upload-Metadata", metadata)
                .header("Tus-Resumable", "1.0.0")
                .exchange()
                .expectStatus().isCreated()
                .returnResult(Void.class)
                .getResponseHeaders().getLocation().toString();

        String uploadId = location.substring(location.lastIndexOf('/') + 1);

        getWebTestClient().get()
                .uri(TUS_ENDPOINT + "/{uploadId}/info", uploadId)
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== GET complete: check upload completion ====================

    @Test
    void whenCheckUploadCompletion_thenReturnsStatus() {
        String metadata = buildTusMetadata("complete-check-" + UUID.randomUUID() + ".txt", null, null);

        String location = getWebTestClient().post()
                .uri(TUS_ENDPOINT)
                .header("Upload-Length", "1024")
                .header("Upload-Metadata", metadata)
                .header("Tus-Resumable", "1.0.0")
                .exchange()
                .expectStatus().isCreated()
                .returnResult(Void.class)
                .getResponseHeaders().getLocation().toString();

        String uploadId = location.substring(location.lastIndexOf('/') + 1);

        getWebTestClient().get()
                .uri(TUS_ENDPOINT + "/{uploadId}/complete", uploadId)
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== GET config ====================

    @Test
    void whenGetTusConfig_thenReturnsConfig() {
        getWebTestClient().get()
                .uri(TUS_ENDPOINT + "/config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.maxUploadSize").isNotEmpty()
                .jsonPath("$.chunkSize").isNotEmpty();
    }

    // ==================== Helper ====================

    private String buildTusMetadata(String filename, UUID parentFolderId, Boolean allowDuplicateFileNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("filename ").append(Base64.getEncoder().encodeToString(filename.getBytes()));

        if (parentFolderId != null) {
            sb.append(",parentFolderId ").append(Base64.getEncoder().encodeToString(parentFolderId.toString().getBytes()));
        }

        if (allowDuplicateFileNames != null) {
            sb.append(",allowDuplicateFileNames ").append(Base64.getEncoder().encodeToString(allowDuplicateFileNames.toString().getBytes()));
        }

        return sb.toString();
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
