package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.*;
import org.openfilz.dms.dto.response.*;
import org.openfilz.dms.enums.DocumentType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
 * E2E tests covering edge cases and error handling branches in:
 * - DocumentServiceImpl (move/copy/rename validation, folder listing filters)
 * - FolderController (listFolder with onlyFiles/onlyFolders)
 * - GlobalExceptionHandler (various exception types)
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class DocumentEdgeCasesIT extends TestContainersBaseConfig {

    public DocumentEdgeCasesIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    // ==================== Folder Creation Edge Cases ====================

    @Test
    void whenCreateFolderWithSlash_thenForbidden() {
        CreateFolderRequest request = new CreateFolderRequest("folder/with/slash", null);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void whenCreateFolderInNonExistentParent_thenNotFound() {
        UUID nonExistentId = UUID.randomUUID();
        CreateFolderRequest request = new CreateFolderRequest("orphan-folder-" + UUID.randomUUID(), nonExistentId);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenCreateDuplicateFolder_thenConflict() {
        String folderName = "duplicate-folder-test-" + UUID.randomUUID();
        CreateFolderRequest request = new CreateFolderRequest(folderName, null);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated();

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    // ==================== Upload Edge Cases ====================

    @Test
    void whenUploadToNonExistentFolder_thenNotFound() {
        UUID nonExistentId = UUID.randomUUID();
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", nonExistentId.toString());

        getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload").build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenCreateBlankDocumentWithSlash_thenForbidden() {
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(
                "doc/with/slash", org.openfilz.dms.enums.DocumentTemplateType.TEXT, null);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void whenCreateBlankDocumentInNonExistentFolder_thenNotFound() {
        UUID nonExistentId = UUID.randomUUID();
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(
                "blank-doc-" + UUID.randomUUID(), org.openfilz.dms.enums.DocumentTemplateType.TEXT, nonExistentId);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenCreateDuplicateBlankDocument_thenConflict() {
        String docName = "duplicate-blank-" + UUID.randomUUID() + ".txt";

        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest(
                docName, org.openfilz.dms.enums.DocumentTemplateType.TEXT, null);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated();

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    // ==================== Move Edge Cases ====================

    @Test
    void whenMoveFolderIntoItself_thenForbidden() {
        FolderResponse folder = createFolder("self-move-test-" + UUID.randomUUID(), null);

        MoveRequest request = new MoveRequest(List.of(folder.id()), folder.id(), null);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders/move")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void whenMoveFolderIntoDescendant_thenForbidden() {
        FolderResponse parent = createFolder("parent-move-desc-" + UUID.randomUUID(), null);
        FolderResponse child = createFolder("child-move-desc-" + UUID.randomUUID(), parent.id());

        MoveRequest request = new MoveRequest(List.of(parent.id()), child.id(), null);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders/move")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void whenMoveFolderToNonExistentTarget_thenNotFound() {
        FolderResponse folder = createFolder("move-to-nowhere-" + UUID.randomUUID(), null);
        UUID nonExistentId = UUID.randomUUID();

        MoveRequest request = new MoveRequest(List.of(folder.id()), nonExistentId, null);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders/move")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenMoveFileToSameFolder_thenError() {
        FolderResponse folder = createFolder("same-folder-move-" + UUID.randomUUID(), null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        UploadResponse file = uploadDocument(builder);

        // Move file to same folder (its current parent)
        MoveRequest request = new MoveRequest(List.of(file.id()), folder.id(), null);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/move")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void whenMoveFileToRoot_thenOk() {
        FolderResponse folder = createFolder("move-file-root-" + UUID.randomUUID(), null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        UploadResponse file = uploadDocument(builder);

        // Move file to root (targetFolderId = null)
        MoveRequest request = new MoveRequest(List.of(file.id()), null, true);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/move")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenMoveNonExistentFile_thenNotFound() {
        FolderResponse folder = createFolder("move-nonexistent-target-" + UUID.randomUUID(), null);
        UUID nonExistentFileId = UUID.randomUUID();

        MoveRequest request = new MoveRequest(List.of(nonExistentFileId), folder.id(), null);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/move")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenMoveFileToNonExistentTarget_thenNotFound() {
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse file = uploadDocument(builder);
        UUID nonExistentId = UUID.randomUUID();

        MoveRequest request = new MoveRequest(List.of(file.id()), nonExistentId, null);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/move")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenMoveFolderWithAllowDuplicates_thenOk() {
        FolderResponse source = createFolder("dup-move-source-" + UUID.randomUUID(), null);
        FolderResponse target = createFolder("dup-move-target-" + UUID.randomUUID(), null);

        // Create a subfolder in source
        FolderResponse subFolder = createFolder("sub-dup-" + UUID.randomUUID(), source.id());

        MoveRequest request = new MoveRequest(List.of(subFolder.id()), target.id(), true);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders/move")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Copy Edge Cases ====================

    @Test
    void whenCopyFolderIntoItself_thenForbidden() {
        FolderResponse folder = createFolder("self-copy-test-" + UUID.randomUUID(), null);

        CopyRequest request = new CopyRequest(List.of(folder.id()), folder.id(), null);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders/copy")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void whenCopyFileToRoot_thenOk() {
        FolderResponse folder = createFolder("copy-file-root-" + UUID.randomUUID(), null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        UploadResponse file = uploadDocument(builder);

        // Copy file to root (targetFolderId = null)
        CopyRequest request = new CopyRequest(List.of(file.id()), null, true);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/copy")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenCopyNonExistentFile_thenNotFound() {
        FolderResponse target = createFolder("copy-target-" + UUID.randomUUID(), null);
        UUID nonExistentFileId = UUID.randomUUID();

        CopyRequest request = new CopyRequest(List.of(nonExistentFileId), target.id(), null);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/copy")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenCopyFolderToNonExistentTarget_thenNotFound() {
        FolderResponse folder = createFolder("copy-nowhere-" + UUID.randomUUID(), null);
        UUID nonExistentId = UUID.randomUUID();

        CopyRequest request = new CopyRequest(List.of(folder.id()), nonExistentId, null);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders/copy")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenCopyFileWithDuplicateName_thenConflict() {
        FolderResponse folder = createFolder("copy-dup-" + UUID.randomUUID(), null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        UploadResponse file = uploadDocument(builder);

        // Copy file to same folder without allowing duplicates
        CopyRequest request = new CopyRequest(List.of(file.id()), folder.id(), false);

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/copy")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    // ==================== Rename Edge Cases ====================

    @Test
    void whenRenameFolderToSameName_thenConflict() {
        String name = "rename-same-" + UUID.randomUUID();
        FolderResponse folder = createFolder(name, null);

        RenameRequest request = new RenameRequest(name);

        getWebTestClient().put().uri(RestApiVersion.API_PREFIX + "/folders/{id}/rename", folder.id())
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void whenRenameNonExistentFile_thenNotFound() {
        UUID nonExistentId = UUID.randomUUID();
        RenameRequest request = new RenameRequest("new-name");

        getWebTestClient().put().uri(RestApiVersion.API_PREFIX + "/files/{id}/rename", nonExistentId)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenRenameNonExistentFolder_thenNotFound() {
        UUID nonExistentId = UUID.randomUUID();
        RenameRequest request = new RenameRequest("new-name");

        getWebTestClient().put().uri(RestApiVersion.API_PREFIX + "/folders/{id}/rename", nonExistentId)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== Replace Content/Metadata Edge Cases ====================

    @Test
    void whenReplaceContentOfNonExistentDocument_thenNotFound() {
        UUID nonExistentId = UUID.randomUUID();
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));

        getWebTestClient().put().uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content", nonExistentId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenReplaceContentOfFolder_thenForbidden() {
        FolderResponse folder = createFolder("replace-content-folder-" + UUID.randomUUID(), null);
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));

        getWebTestClient().put().uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content", folder.id())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void whenReplaceMetadataOfNonExistentDocument_thenNotFound() {
        UUID nonExistentId = UUID.randomUUID();

        getWebTestClient().put().uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-metadata", nonExistentId)
                .bodyValue(Map.of("key", "value"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenUpdateMetadataOfNonExistentDocument_thenNotFound() {
        UUID nonExistentId = UUID.randomUUID();
        UpdateMetadataRequest request = new UpdateMetadataRequest(Map.of("key", "value"));

        getWebTestClient().patch().uri(RestApiVersion.API_PREFIX + "/documents/{id}/metadata", nonExistentId)
                .bodyValue(request)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenDeleteMetadataOfNonExistentDocument_thenNotFound() {
        UUID nonExistentId = UUID.randomUUID();
        DeleteMetadataRequest request = new DeleteMetadataRequest(List.of("key1"));

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/documents/{id}/metadata", nonExistentId)
                .bodyValue(request)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenDeleteMetadataOfDocumentWithNoMetadata_thenOk() {
        // Upload a file without metadata
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse file = uploadDocument(builder);

        // Delete metadata key that doesn't exist
        DeleteMetadataRequest request = new DeleteMetadataRequest(List.of("nonExistentKey"));

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/documents/{id}/metadata", file.id())
                .bodyValue(request)
                .exchange()
                .expectStatus().isNoContent();
    }

    // ==================== Document Info Edge Cases ====================

    @Test
    void whenGetDocumentInfoWithoutMetadata_thenOk() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", Map.of("key1", "value1"));
        UploadResponse file = uploadDocument(builder);

        // Get info WITHOUT metadata
        DocumentInfo info = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                                .queryParam("withMetadata", false)
                                .build(file.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(info);
        Assertions.assertEquals(file.name(), info.name());
        Assertions.assertNull(info.metadata(), "Metadata should be null when withMetadata=false");
    }

    @Test
    void whenGetDocumentInfoNonExistent_thenNotFound() {
        UUID nonExistentId = UUID.randomUUID();

        getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                                .queryParam("withMetadata", true)
                                .build(nonExistentId))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenGetDocumentMetadataNonExistent_thenNotFound() {
        UUID nonExistentId = UUID.randomUUID();

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/{id}/search/metadata", nonExistentId)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenGetDocumentMetadataWithKeyFilter_thenFiltered() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", Map.of("key1", "value1", "key2", "value2", "key3", "value3"));
        UploadResponse file = uploadDocument(builder);

        SearchMetadataRequest request = new SearchMetadataRequest(List.of("key1", "key3"));

        Map metadata = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/{id}/search/metadata", file.id())
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(metadata);
        Assertions.assertEquals(2, metadata.size());
        Assertions.assertEquals("value1", metadata.get("key1"));
        Assertions.assertEquals("value3", metadata.get("key3"));
        Assertions.assertNull(metadata.get("key2"));
    }

    // ==================== List Folder Filters ====================

    @Test
    void whenListFolderOnlyFiles_thenOnlyFilesReturned() {
        String folderName = "only-files-test-" + UUID.randomUUID();
        FolderResponse parent = createFolder(folderName, null);

        // Create a subfolder
        createFolder("subfolder-" + UUID.randomUUID(), parent.id());

        // Upload a file
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", parent.id().toString());
        uploadDocument(builder);

        List<FolderElementInfo> elements = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                                .queryParam("folderId", parent.id())
                                .queryParam("onlyFiles", true)
                                .build())
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(FolderElementInfo.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(elements);
        Assertions.assertEquals(1, elements.size());
        Assertions.assertEquals(DocumentType.FILE, elements.get(0).type());
    }

    @Test
    void whenListFolderOnlyFolders_thenOnlyFoldersReturned() {
        String folderName = "only-folders-test-" + UUID.randomUUID();
        FolderResponse parent = createFolder(folderName, null);

        // Create a subfolder
        FolderResponse sub = createFolder("subfolder-" + UUID.randomUUID(), parent.id());

        // Upload a file
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", parent.id().toString());
        uploadDocument(builder);

        List<FolderElementInfo> elements = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                                .queryParam("folderId", parent.id())
                                .queryParam("onlyFolders", true)
                                .build())
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(FolderElementInfo.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(elements);
        Assertions.assertEquals(1, elements.size());
        Assertions.assertEquals(DocumentType.FOLDER, elements.get(0).type());
        Assertions.assertEquals(sub.id(), elements.get(0).id());
    }

    @Test
    void whenListFolderBothFiltersTrue_thenBadRequest() {
        getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                                .queryParam("onlyFiles", true)
                                .queryParam("onlyFolders", true)
                                .build())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void whenListNonExistentFolder_thenNotFound() {
        UUID nonExistentId = UUID.randomUUID();

        getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                                .queryParam("folderId", nonExistentId)
                                .build())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenListRootFolder_thenOk() {
        // Listing root (no folderId) should work
        List<FolderElementInfo> elements = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                                .build())
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(FolderElementInfo.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(elements);
    }

    // ==================== Download Edge Cases ====================

    @Test
    void whenDownloadMultipleWithEmptyList_thenBadRequest() {
        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/documents/download-multiple")
                .bodyValue(Collections.emptyList())
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ==================== Delete Edge Cases ====================

    @Test
    void whenDeleteNonExistentFile_thenNotFound() {
        UUID nonExistentId = UUID.randomUUID();
        DeleteRequest request = new DeleteRequest(List.of(nonExistentId));

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isNotFound();
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
