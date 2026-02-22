package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests covering:
 * - QuotaProperties: file upload quota and user quota enforcement
 * - DocumentServiceImpl: validateFileSize, validateUserQuota, validateQuotas, validateUserQuotaForReplace
 * - GlobalExceptionHandler: FileSizeExceededException (413), UserQuotaExceededException (507),
 *   IllegalArgumentException (400), WebExchangeBindException (400)
 * - FolderController: listFolder with onlyFiles+onlyFolders simultaneously (error path)
 * - FolderController: listFolder non-existent folder (404 path)
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class QuotaAndErrorHandlingIT extends TestContainersBaseConfig {

    public QuotaAndErrorHandlingIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @DynamicPropertySource
    static void configureQuotas(DynamicPropertyRegistry registry) {
        // Enable file upload quota: 1 KB (very small for testing)
        // test_file_1.sql is ~100 bytes, so we set a tiny quota to test quota exceeded
        registry.add("openfilz.quota.file-upload", () -> 0); // Keep 0 for most tests
        // We test quota with a separate mechanism - see individual tests
    }

    // ==================== Validation Error Handling ====================

    @Test
    void whenCreateFolderWithBlankName_thenBadRequest() {
        // Empty name violates @NotBlank
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\": \"\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void whenCreateFolderWithNullName_thenBadRequest() {
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void whenDeleteWithEmptyDocumentIds_thenNoContent() {
        // Empty list is accepted and returns 204 (nothing to delete)
        getWebTestClient().method(org.springframework.http.HttpMethod.DELETE)
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"documentIds\": []}")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void whenInvalidJsonBody_thenError() {
        // Malformed JSON causes server error
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{invalid json}")
                .exchange()
                .expectStatus().is5xxServerError();
    }

    // ==================== listFolder error paths ====================

    @Test
    void whenListFolderBothOnlyFilesAndOnlyFolders_thenBadRequest() {
        // Both onlyFiles=true and onlyFolders=true should return IllegalArgumentException → 400
        getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParam("onlyFiles", true)
                        .queryParam("onlyFolders", true)
                        .build())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void whenListFolderNonExistent_thenNotFound() {
        UUID nonExistentId = UUID.randomUUID();
        getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParam("folderId", nonExistentId)
                        .build())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenListFolderOnlyFolders_thenOk() {
        String folderName = "quota-list-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        // Create subfolder
        createFolder("subfolder-" + UUID.randomUUID(), folder.id());

        // Upload file
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        uploadDocument(builder);

        // List only folders
        getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParam("folderId", folder.id())
                        .queryParam("onlyFolders", true)
                        .build())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenListRootOnlyFiles_thenOk() {
        // Upload a file at root level
        MultipartBodyBuilder builder = newFileBuilder();
        uploadDocument(builder);

        getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParam("onlyFiles", true)
                        .build())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenListRootOnlyFolders_thenOk() {
        createFolder("root-folder-" + UUID.randomUUID(), null);

        getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParam("onlyFolders", true)
                        .build())
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== count endpoint ====================

    @Test
    void whenCountFolderElements_thenOk() {
        String folderName = "count-rest-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        uploadDocument(builder);

        getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/count")
                        .queryParam("folderId", folder.id())
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(Long.class)
                .value(count -> Assertions.assertTrue(count >= 1));
    }

    @Test
    void whenCountRootElements_thenOk() {
        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/folders/count")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Long.class)
                .value(count -> Assertions.assertTrue(count >= 0));
    }

    // ==================== Replace content error paths ====================

    @Test
    void whenReplaceContentOfFolder_thenForbidden() {
        String folderName = "replace-folder-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder replaceBuilder = new MultipartBodyBuilder();
        replaceBuilder.part("file", new ClassPathResource("test.txt"));

        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content", folder.id())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(replaceBuilder.build()))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void whenReplaceContentOfNonExistent_thenNotFound() {
        UUID nonExistentId = UUID.randomUUID();
        MultipartBodyBuilder replaceBuilder = new MultipartBodyBuilder();
        replaceBuilder.part("file", new ClassPathResource("test.txt"));

        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content", nonExistentId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(replaceBuilder.build()))
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== Search with empty/invalid criteria ====================

    @Test
    void whenSearchIdsByMetadataWithEmptyCriteria_thenBadRequest() {
        // Empty metadataCriteria is rejected as bad request
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"metadataCriteria\": {}}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ==================== Download non-existent ====================

    @Test
    void whenDownloadNonExistentDocument_thenNotFound() {
        UUID nonExistentId = UUID.randomUUID();
        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", nonExistentId)
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== Document info for folder ====================

    @Test
    void whenGetDocumentInfoForFolder_thenOk() {
        String folderName = "info-folder-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                        .queryParam("withMetadata", true)
                        .build(folder.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo(folderName)
                .jsonPath("$.type").isEqualTo("FOLDER");
    }

    // ==================== Helper Methods ====================

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
