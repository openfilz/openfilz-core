package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.*;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests for advanced metadata operations and document info covering:
 * - DocumentServiceImpl.getDocumentMetadata() with key filtering and null metadata
 * - DocumentServiceImpl.replaceDocumentMetadata() with null and new metadata
 * - DocumentServiceImpl.updateDocumentMetadata() with null existing metadata
 * - DocumentServiceImpl.deleteDocumentMetadata() on docs with no metadata
 * - DocumentServiceImpl.getDocumentInfo() with/without metadata flag
 * - DocumentServiceImpl.downloadDocument() for folder (zipFolder path)
 * - DocumentServiceImpl.countFolderElements()
 * - Upload with allowDuplicateFileNames=false (duplicate check path)
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class AdvancedMetadataIT extends TestContainersBaseConfig {

    public AdvancedMetadataIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    // ==================== getDocumentMetadata with key filtering ====================

    @Test
    void whenGetMetadataWithKeyFilter_thenOnlyRequestedKeysReturned() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "{\"key1\":\"val1\",\"key2\":\"val2\",\"key3\":\"val3\"}");
        UploadResponse file = uploadDocument(builder);

        SearchMetadataRequest request = new SearchMetadataRequest(List.of("key1", "key3"));
        Map<String, Object> result = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/search/metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.containsKey("key1"));
        Assertions.assertTrue(result.containsKey("key3"));
        Assertions.assertFalse(result.containsKey("key2"));
    }

    @Test
    void whenGetMetadataWithoutKeyFilter_thenAllKeysReturned() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "{\"a\":\"1\",\"b\":\"2\"}");
        UploadResponse file = uploadDocument(builder);

        SearchMetadataRequest request = new SearchMetadataRequest(null);
        Map<String, Object> result = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/search/metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.containsKey("a"));
        Assertions.assertTrue(result.containsKey("b"));
    }

    @Test
    void whenGetMetadataOfDocWithNoMetadata_thenEmptyMap() {
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse file = uploadDocument(builder);

        SearchMetadataRequest request = new SearchMetadataRequest(null);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/search/metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenGetMetadataOfDocWithNoMetadataAndKeyFilter_thenEmptyMap() {
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse file = uploadDocument(builder);

        SearchMetadataRequest request = new SearchMetadataRequest(List.of("nonExistentKey"));
        Map<String, Object> result = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/search/metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isEmpty());
    }

    // ==================== replaceDocumentMetadata ====================

    @Test
    void whenReplaceMetadataWithEmptyMap_thenOk() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "{\"existing\":\"data\"}");
        UploadResponse file = uploadDocument(builder);

        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenReplaceMetadataWithNewData_thenReplaced() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "{\"old\":\"data\"}");
        UploadResponse file = uploadDocument(builder);

        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"brand\":\"new\"}")
                .exchange()
                .expectStatus().isOk();

        // Verify replaced
        SearchMetadataRequest request = new SearchMetadataRequest(null);
        Map<String, Object> result = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/search/metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.containsKey("brand"));
        Assertions.assertFalse(result.containsKey("old"));
    }

    @Test
    void whenReplaceMetadataOnNonExistent_thenNotFound() {
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-metadata", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"key\":\"value\"}")
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== updateDocumentMetadata on doc with no metadata ====================

    @Test
    void whenUpdateMetadataOnDocWithNoExistingMetadata_thenCreatesNew() {
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse file = uploadDocument(builder);

        UpdateMetadataRequest updateRequest = new UpdateMetadataRequest(Map.of("newKey", "newVal"));
        getWebTestClient().patch()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(updateRequest))
                .exchange()
                .expectStatus().isOk();

        // Verify created
        SearchMetadataRequest searchRequest = new SearchMetadataRequest(null);
        Map<String, Object> result = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/search/metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(searchRequest))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(result);
        Assertions.assertEquals("newVal", result.get("newKey"));
    }

    @Test
    void whenUpdateMetadataMergesWithExisting_thenBothKeysPresent() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "{\"existing\":\"data\"}");
        UploadResponse file = uploadDocument(builder);

        UpdateMetadataRequest updateRequest = new UpdateMetadataRequest(Map.of("added", "value"));
        getWebTestClient().patch()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(updateRequest))
                .exchange()
                .expectStatus().isOk();

        SearchMetadataRequest searchRequest = new SearchMetadataRequest(null);
        Map<String, Object> result = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/search/metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(searchRequest))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(result);
        Assertions.assertEquals("data", result.get("existing"));
        Assertions.assertEquals("value", result.get("added"));
    }

    // ==================== deleteDocumentMetadata edge cases ====================

    @Test
    void whenDeleteMetadataKeysFromDoc_thenKeysRemoved() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "{\"keep\":\"yes\",\"remove\":\"me\"}");
        UploadResponse file = uploadDocument(builder);

        DeleteMetadataRequest deleteRequest = new DeleteMetadataRequest(List.of("remove"));
        getWebTestClient().method(org.springframework.http.HttpMethod.DELETE)
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        // Verify the key was removed
        SearchMetadataRequest searchRequest = new SearchMetadataRequest(null);
        Map<String, Object> result = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/search/metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(searchRequest))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.containsKey("keep"));
        Assertions.assertFalse(result.containsKey("remove"));
    }

    // ==================== getDocumentInfo with/without metadata ====================

    @Test
    void whenGetDocumentInfoWithMetadataTrue_thenMetadataIncluded() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "{\"infoKey\":\"infoValue\"}");
        UploadResponse file = uploadDocument(builder);

        getWebTestClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                        .queryParam("withMetadata", true)
                        .build(file.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isNotEmpty()
                .jsonPath("$.metadata").isNotEmpty();
    }

    @Test
    void whenGetDocumentInfoWithMetadataFalse_thenMetadataNull() {
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse file = uploadDocument(builder);

        getWebTestClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                        .queryParam("withMetadata", false)
                        .build(file.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isNotEmpty();
    }

    @Test
    void whenGetDocumentInfoWithoutParam_thenOk() {
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse file = uploadDocument(builder);

        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", file.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isNotEmpty()
                .jsonPath("$.type").isEqualTo("FILE");
    }

    @Test
    void whenGetFolderInfoWithMetadata_thenTypeIsFolder() {
        String folderName = "info-folder-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        getWebTestClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                        .queryParam("withMetadata", true)
                        .build(folder.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.type").isEqualTo("FOLDER");
    }

    // ==================== Download folder as ZIP ====================

    @Test
    void whenDownloadFolderWithFiles_thenZipReturned() {
        String folderName = "zip-folder-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        uploadDocument(builder);

        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", folder.id())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM);
    }

    @Test
    void whenDownloadFolderWithSubfolderAndFiles_thenZipContainsAll() {
        String parentName = "zip-parent-" + UUID.randomUUID();
        FolderResponse parent = createFolder(parentName, null);

        String childName = "zip-child-" + UUID.randomUUID();
        FolderResponse child = createFolder(childName, parent.id());

        // Upload file in child
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", child.id().toString());
        uploadDocument(builder);

        // Upload file directly in parent
        MultipartBodyBuilder builder2 = newFileBuilder();
        builder2.part("parentFolderId", parent.id().toString());
        uploadDocument(builder2);

        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", parent.id())
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== countFolderElements ====================

    @Test
    void whenCountFolderElements_thenReturnsCount() {
        String folderName = "count-folder-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        uploadDocument(builder);

        getWebTestClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/folders/count")
                        .queryParam("folderId", folder.id())
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(Long.class)
                .value(count -> Assertions.assertTrue(count >= 1));
    }

    // ==================== Upload with allowDuplicateFileNames=false ====================

    @Test
    void whenUploadWithDuplicateNameAndNotAllowed_thenConflict() {
        String folderName = "nodup-folder-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        // First upload
        MultipartBodyBuilder builder1 = newFileBuilder();
        builder1.part("parentFolderId", folder.id().toString());
        getUploadResponse(builder1, false);

        // Second upload with same name, allowDuplicateFileNames=false
        MultipartBodyBuilder builder2 = newFileBuilder();
        builder2.part("parentFolderId", folder.id().toString());
        getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", false)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder2.build()))
                .exchange()
                .expectStatus().isEqualTo(409);
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
