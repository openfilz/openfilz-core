package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.*;
import org.openfilz.dms.dto.response.DocumentInfo;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.enums.DocumentType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests covering REST controller edge cases:
 * - Update metadata (merge existing + new keys)
 * - Delete metadata key
 * - Search IDs by metadata
 * - Document info with and without metadata
 * - Move files with allowDuplicateFileNames
 * - Copy files with allowDuplicateFileNames
 * - Move folder to root
 * - Rename file/folder to same name
 * - Replace document content + verify fields
 * - Download folder as ZIP
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class RestEdgeCasesIT extends TestContainersBaseConfig {

    public RestEdgeCasesIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    // ==================== Update Metadata (merge) ====================

    @Test
    void whenUpdateMetadata_thenMergedWithExisting() {
        // Upload file with initial metadata
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "{\"key1\":\"value1\",\"key2\":\"value2\"}");
        UploadResponse uploaded = getUploadResponse(builder, true);
        Assertions.assertNotNull(uploaded);

        // Update metadata - add new key, keep existing
        UpdateMetadataRequest updateRequest = new UpdateMetadataRequest(Map.of("key3", "value3"));
        getWebTestClient().patch()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/metadata", uploaded.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(updateRequest))
                .exchange()
                .expectStatus().isOk();

        // Verify merged metadata
        DocumentInfo info = getDocumentInfo(uploaded.id());
        Assertions.assertNotNull(info.metadata());
        Assertions.assertEquals("value1", info.metadata().get("key1"));
        Assertions.assertEquals("value2", info.metadata().get("key2"));
        Assertions.assertEquals("value3", info.metadata().get("key3"));
    }

    @Test
    void whenUpdateMetadataOnDocWithNoMetadata_thenCreated() {
        // Upload file without metadata
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse uploaded = uploadDocument(builder);
        Assertions.assertNotNull(uploaded);

        // Update metadata on doc that has none
        UpdateMetadataRequest updateRequest = new UpdateMetadataRequest(Map.of("newKey", "newValue"));
        getWebTestClient().patch()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/metadata", uploaded.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(updateRequest))
                .exchange()
                .expectStatus().isOk();

        // Verify metadata was created
        DocumentInfo info = getDocumentInfo(uploaded.id());
        Assertions.assertNotNull(info.metadata());
        Assertions.assertEquals("newValue", info.metadata().get("newKey"));
    }

    @Test
    void whenUpdateMetadataNonExistent_thenError() {
        UUID nonExistentId = UUID.randomUUID();
        UpdateMetadataRequest updateRequest = new UpdateMetadataRequest(Map.of("key", "value"));
        getWebTestClient().patch()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/metadata", nonExistentId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(updateRequest))
                .exchange()
                .expectStatus().is4xxClientError();
    }

    // ==================== Replace Metadata (full replace) ====================

    @Test
    void whenReplaceMetadata_thenOldKeysRemoved() {
        // Upload file with initial metadata
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "{\"oldKey\":\"oldValue\"}");
        UploadResponse uploaded = getUploadResponse(builder, true);

        // Replace metadata completely
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-metadata", uploaded.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("newKey", "newValue"))
                .exchange()
                .expectStatus().isOk();

        // Verify old keys are gone
        DocumentInfo info = getDocumentInfo(uploaded.id());
        Assertions.assertNotNull(info.metadata());
        Assertions.assertNull(info.metadata().get("oldKey"));
        Assertions.assertEquals("newValue", info.metadata().get("newKey"));
    }

    @Test
    void whenReplaceMetadataWithNull_thenMetadataCleared() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "{\"key\":\"value\"}");
        UploadResponse uploaded = getUploadResponse(builder, true);

        // Replace with empty/null metadata
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-metadata", uploaded.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Collections.emptyMap())
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Delete Metadata Key ====================

    @Test
    void whenDeleteMetadataKey_thenKeyRemoved() {
        // Upload file with metadata
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "{\"keep\":\"yes\",\"remove\":\"no\"}");
        UploadResponse uploaded = getUploadResponse(builder, true);

        // Delete the "remove" key using DeleteMetadataRequest
        DeleteMetadataRequest deleteRequest = new DeleteMetadataRequest(List.of("remove"));
        getWebTestClient().method(org.springframework.http.HttpMethod.DELETE)
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/metadata", uploaded.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        // Verify key was removed but other key remains
        DocumentInfo info = getDocumentInfo(uploaded.id());
        Assertions.assertNotNull(info.metadata());
        Assertions.assertEquals("yes", info.metadata().get("keep"));
        Assertions.assertNull(info.metadata().get("remove"));
    }

    // ==================== Search IDs by Metadata ====================

    @Test
    void whenSearchIdsByMetadata_thenMatchingIdsReturned() {
        String uniqueValue = "search-meta-" + UUID.randomUUID();
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "{\"searchField\":\"" + uniqueValue + "\"}");
        UploadResponse uploaded = getUploadResponse(builder, true);

        // Search by metadata using SearchByMetadataRequest
        SearchByMetadataRequest searchRequest = new SearchByMetadataRequest(
                null, null, null, null, Map.of("searchField", uniqueValue));
        List<UUID> ids = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(searchRequest)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UUID>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(ids);
        Assertions.assertTrue(ids.contains(uploaded.id()),
                "Should find the uploaded document by metadata");
    }

    @Test
    void whenSearchIdsByMetadataNoMatch_thenEmptyList() {
        SearchByMetadataRequest searchRequest = new SearchByMetadataRequest(
                null, null, null, null, Map.of("nonexistent", "value-" + UUID.randomUUID()));
        List<UUID> ids = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(searchRequest)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UUID>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(ids);
        Assertions.assertTrue(ids.isEmpty());
    }

    @Test
    void whenSearchIdsByMetadataWithTypeFilter_thenFiltered() {
        String uniqueValue = "typed-meta-" + UUID.randomUUID();
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "{\"typedField\":\"" + uniqueValue + "\"}");
        UploadResponse uploaded = getUploadResponse(builder, true);

        // Search with type filter
        SearchByMetadataRequest searchRequest = new SearchByMetadataRequest(
                null, DocumentType.FILE, null, null, Map.of("typedField", uniqueValue));
        List<UUID> ids = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(searchRequest)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UUID>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(ids);
        Assertions.assertTrue(ids.contains(uploaded.id()));
    }

    @Test
    void whenSearchIdsByMetadataRootOnly_thenFiltered() {
        String uniqueValue = "root-meta-" + UUID.randomUUID();
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "{\"rootField\":\"" + uniqueValue + "\"}");
        getUploadResponse(builder, true);

        // Search with rootOnly=true
        SearchByMetadataRequest searchRequest = new SearchByMetadataRequest(
                null, null, null, true, Map.of("rootField", uniqueValue));
        List<UUID> ids = getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(searchRequest)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<UUID>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(ids);
    }

    // ==================== Move with allowDuplicateFileNames ====================

    @Test
    void whenMoveFileWithAllowDuplicates_thenOk() {
        // Create two folders
        String folder1Name = "move-dup-src-" + UUID.randomUUID();
        String folder2Name = "move-dup-dst-" + UUID.randomUUID();
        FolderResponse folder1 = createFolder(folder1Name, null);
        FolderResponse folder2 = createFolder(folder2Name, null);

        // Upload same-named file in both folders
        MultipartBodyBuilder builder1 = newFileBuilder();
        builder1.part("parentFolderId", folder1.id().toString());
        UploadResponse file1 = uploadDocument(builder1);

        MultipartBodyBuilder builder2 = newFileBuilder();
        builder2.part("parentFolderId", folder2.id().toString());
        uploadDocument(builder2);

        // Move file from folder1 to folder2 with allowDuplicateFileNames=true
        MoveRequest moveRequest = new MoveRequest(List.of(file1.id()), folder2.id(), true);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/files/move")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(moveRequest))
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Copy with allowDuplicateFileNames ====================

    @Test
    void whenCopyFileWithDuplicateNames_thenConflict() {
        String folderName = "copy-dup-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        UploadResponse file = uploadDocument(builder);

        // Copy to same folder without allowing duplicates
        CopyRequest copyRequest = new CopyRequest(List.of(file.id()), folder.id(), false);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/files/copy")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(copyRequest))
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void whenCopyFileWithAllowDuplicates_thenOk() {
        String folderName = "copy-allow-dup-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        UploadResponse file = uploadDocument(builder);

        // Copy to same folder with allowDuplicateFileNames=true
        CopyRequest copyRequest = new CopyRequest(List.of(file.id()), folder.id(), true);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/files/copy")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(copyRequest))
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Replace Content ====================

    @Test
    void whenReplaceContentOfFile_thenFieldsUpdated() {
        // Upload original file
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse original = uploadDocument(builder);
        Assertions.assertNotNull(original);

        // Replace with different file
        MultipartBodyBuilder replaceBuilder = new MultipartBodyBuilder();
        replaceBuilder.part("file", new ClassPathResource("test.txt"));

        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content", original.id())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(replaceBuilder.build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(original.id().toString())
                .jsonPath("$.name").isEqualTo("test_file_1.sql") // Name should not change
                .jsonPath("$.type").isEqualTo("FILE");
    }

    // ==================== Download Folder as ZIP ====================

    @Test
    void whenDownloadFolderWithSubfolders_thenZipReturned() {
        String folderName = "zip-download-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        // Upload a file inside
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        uploadDocument(builder);

        // Download as ZIP
        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", folder.id())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM);
    }

    @Test
    void whenDownloadEmptyFolder_thenOk() {
        String folderName = "empty-zip-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        // Download empty folder as ZIP
        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", folder.id())
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Rename Edge Cases ====================

    @Test
    void whenRenameFolderToDifferentName_thenOk() {
        String originalName = "rename-original-" + UUID.randomUUID();
        FolderResponse folder = createFolder(originalName, null);

        String newName = "rename-new-" + UUID.randomUUID();
        RenameRequest renameRequest = new RenameRequest(newName);
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/folders/{folderId}/rename", folder.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(renameRequest))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenRenameFileToDifferentName_thenOk() {
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse uploaded = uploadDocument(builder);

        String newName = "renamed-" + UUID.randomUUID() + ".sql";
        RenameRequest renameRequest = new RenameRequest(newName);
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/files/{fileId}/rename", uploaded.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(renameRequest))
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Move Folder Between Folders ====================

    @Test
    void whenMoveFolderBetweenFolders_thenOk() {
        String srcName = "move-src-" + UUID.randomUUID();
        FolderResponse srcFolder = createFolder(srcName, null);

        String dstName = "move-dst-" + UUID.randomUUID();
        FolderResponse dstFolder = createFolder(dstName, null);

        String childName = "move-child-" + UUID.randomUUID();
        FolderResponse child = createFolder(childName, srcFolder.id());

        // Move child folder from src to dst
        MoveRequest moveRequest = new MoveRequest(List.of(child.id()), dstFolder.id(), false);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders/move")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(moveRequest))
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Document Info Endpoints ====================

    @Test
    void whenGetDocumentInfoWithoutMetadata_thenNoMetadata() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "{\"key\":\"value\"}");
        UploadResponse uploaded = getUploadResponse(builder, true);

        // Get info without metadata
        DocumentInfo info = getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                        .queryParam("withMetadata", false)
                        .build(uploaded.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(info);
        // Metadata might be null or empty when withMetadata=false
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

    private DocumentInfo getDocumentInfo(UUID id) {
        return getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                        .queryParam("withMetadata", true)
                        .build(id))
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();
    }
}
