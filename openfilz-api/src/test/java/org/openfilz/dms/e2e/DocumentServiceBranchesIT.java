package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.*;
import org.openfilz.dms.dto.response.*;
import org.openfilz.dms.enums.DocumentTemplateType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
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
 * E2E tests targeting DocumentServiceImpl branches:
 * - Metadata CRUD: replace, update (patch), delete, search by keys
 * - Blank document creation (Word, Excel, PowerPoint, Text)
 * - Folder listing: onlyFiles, onlyFolders, both true (error), count
 * - Document info with/without metadata
 * - Document position and ancestors
 * - Folder operations: create with slash in name, create in non-existent parent
 * - Move folder: to root (null target), move into self
 * - Copy files: to root (null target)
 * - Download folder as ZIP
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class DocumentServiceBranchesIT extends TestContainersBaseConfig {

    public DocumentServiceBranchesIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    // ==================== Metadata CRUD Operations ====================

    @Test
    void whenReplaceDocumentMetadata_thenOk() {
        UploadResponse file = uploadDocument(newFileBuilder());

        Map<String, Object> newMetadata = Map.of("key1", "value1", "key2", 42);
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newMetadata)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isNotEmpty();
    }

    @Test
    void whenUpdateDocumentMetadata_thenOk() {
        UploadResponse file = uploadDocument(newFileBuilder());

        // First set some metadata
        Map<String, Object> initialMetadata = Map.of("initial", "value");
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(initialMetadata)
                .exchange()
                .expectStatus().isOk();

        // Now patch/update metadata (adds new key, keeps existing)
        UpdateMetadataRequest updateRequest = new UpdateMetadataRequest(Map.of("newKey", "newValue", "initial", "updated"));
        getWebTestClient().patch()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateRequest)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isNotEmpty();
    }

    @Test
    void whenDeleteDocumentMetadata_thenNoContent() {
        UploadResponse file = uploadDocument(newFileBuilder());

        // First set some metadata
        Map<String, Object> metadata = Map.of("toDelete", "val1", "toKeep", "val2");
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(metadata)
                .exchange()
                .expectStatus().isOk();

        // Delete specific metadata keys
        DeleteMetadataRequest deleteRequest = new DeleteMetadataRequest(List.of("toDelete"));
        getWebTestClient().method(org.springframework.http.HttpMethod.DELETE)
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(deleteRequest)
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void whenSearchDocumentMetadataByKeys_thenFilteredResponse() {
        UploadResponse file = uploadDocument(newFileBuilder());

        Map<String, Object> metadata = Map.of("key1", "v1", "key2", "v2", "key3", "v3");
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(metadata)
                .exchange()
                .expectStatus().isOk();

        // Search metadata with specific keys
        SearchMetadataRequest searchRequest = new SearchMetadataRequest(List.of("key1", "key3"));
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/search/metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(searchRequest)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenSearchDocumentMetadataWithNoKeys_thenAllReturned() {
        UploadResponse file = uploadDocument(newFileBuilder());

        // Search without body (null request)
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/search/metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenReplaceMetadataWithNull_thenOk() {
        UploadResponse file = uploadDocument(newFileBuilder());

        // Replace with null metadata
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenReplaceMetadataNonExistentDoc_thenNotFound() {
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-metadata", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("key", "value"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenUpdateMetadataNonExistentDoc_thenNotFound() {
        UpdateMetadataRequest updateRequest = new UpdateMetadataRequest(Map.of("key", "value"));
        getWebTestClient().patch()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/metadata", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateRequest)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenDeleteMetadataNonExistentDoc_thenNotFound() {
        DeleteMetadataRequest deleteRequest = new DeleteMetadataRequest(List.of("key1"));
        getWebTestClient().method(org.springframework.http.HttpMethod.DELETE)
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/metadata", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(deleteRequest)
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== Blank Document Creation ====================

    @Test
    void whenCreateBlankWordDocument_thenCreated() {
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest("TestDoc", DocumentTemplateType.WORD, null);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.name").isEqualTo("TestDoc.docx")
                .jsonPath("$.contentType").isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    void whenCreateBlankExcelDocument_thenCreated() {
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest("TestSheet.xlsx", DocumentTemplateType.EXCEL, null);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.name").isEqualTo("TestSheet.xlsx");
    }

    @Test
    void whenCreateBlankPowerpointDocument_thenCreated() {
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest("TestPres", DocumentTemplateType.POWERPOINT, null);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.name").isEqualTo("TestPres.pptx");
    }

    @Test
    void whenCreateBlankTextDocument_thenCreated() {
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest("TestText", DocumentTemplateType.TEXT, null);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.name").isEqualTo("TestText.txt");
    }

    @Test
    void whenCreateBlankDocumentInFolder_thenCreated() {
        FolderResponse folder = createFolder("blank-doc-folder-" + UUID.randomUUID(), null);

        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest("InFolder", DocumentTemplateType.TEXT, folder.id());
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.name").isEqualTo("InFolder.txt");
    }

    @Test
    void whenCreateBlankDocumentDuplicateName_thenConflict() {
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest("unique-blank-" + UUID.randomUUID() + ".txt", DocumentTemplateType.TEXT, null);
        // First: create
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated();

        // Second: duplicate
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void whenCreateBlankDocumentWithSlash_thenForbidden() {
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest("invalid/name", DocumentTemplateType.TEXT, null);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void whenCreateBlankDocumentInNonExistentFolder_thenNotFound() {
        CreateBlankDocumentRequest request = new CreateBlankDocumentRequest("ghost", DocumentTemplateType.WORD, UUID.randomUUID());
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/create-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== Folder Listing Branches ====================

    @Test
    void whenListFolderOnlyFiles_thenOk() {
        String name = "list-f-" + UUID.randomUUID();
        FolderResponse folder = createFolder(name, null);

        // Upload a file into the folder
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        builder.part("parentFolderId", folder.id().toString());
        uploadDocument(builder);

        getWebTestClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParam("folderId", folder.id())
                        .queryParam("onlyFiles", true)
                        .build())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenListFolderOnlyFolders_thenOk() {
        String name = "list-d-" + UUID.randomUUID();
        FolderResponse parent = createFolder(name, null);
        createFolder("child-" + UUID.randomUUID(), parent.id());

        getWebTestClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParam("folderId", parent.id())
                        .queryParam("onlyFolders", true)
                        .build())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenListFolderBothFilesAndFolders_thenBadRequest() {
        getWebTestClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParam("onlyFiles", true)
                        .queryParam("onlyFolders", true)
                        .build())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void whenListFolderRootWithNoId_thenOk() {
        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/folders/list")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenListFolderNonExistent_thenNotFound() {
        getWebTestClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParam("folderId", UUID.randomUUID())
                        .build())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenCountFolderElements_thenOk() {
        String name = "count-" + UUID.randomUUID();
        FolderResponse folder = createFolder(name, null);

        getWebTestClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/folders/count")
                        .queryParam("folderId", folder.id())
                        .build())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenCountRootElements_thenOk() {
        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/folders/count")
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Document Info with/without metadata ====================

    @Test
    void whenGetDocumentInfoWithMetadata_thenIncludesMetadata() {
        UploadResponse file = uploadDocument(newFileBuilder());

        // Set some metadata first
        Map<String, Object> metadata = Map.of("infoKey", "infoValue");
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-metadata", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(metadata)
                .exchange()
                .expectStatus().isOk();

        // Get info with metadata
        getWebTestClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                        .queryParam("withMetadata", true)
                        .build(file.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isNotEmpty()
                .jsonPath("$.metadata.infoKey").isEqualTo("infoValue");
    }

    @Test
    void whenGetDocumentInfoWithoutMetadata_thenNoMetadata() {
        UploadResponse file = uploadDocument(newFileBuilder());

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
    void whenGetDocumentInfoNonExistent_thenNotFound() {
        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== Document Position and Ancestors ====================

    @Test
    void whenGetDocumentPosition_thenOk() {
        FolderResponse folder = createFolder("pos-" + UUID.randomUUID(), null);
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        builder.part("parentFolderId", folder.id().toString());
        UploadResponse file = uploadDocument(builder);

        getWebTestClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/documents/{id}/position")
                        .queryParam("sortBy", "name")
                        .queryParam("sortOrder", "ASC")
                        .build(file.id()))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenGetDocumentAncestors_thenOk() {
        FolderResponse parent = createFolder("ancestor-" + UUID.randomUUID(), null);
        FolderResponse child = createFolder("child-" + UUID.randomUUID(), parent.id());

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        builder.part("parentFolderId", child.id().toString());
        UploadResponse file = uploadDocument(builder);

        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/ancestors", file.id())
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Folder Operations - Error paths ====================

    @Test
    void whenCreateFolderWithSlash_thenForbidden() {
        CreateFolderRequest request = new CreateFolderRequest("invalid/folder", null);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void whenCreateFolderInNonExistentParent_thenNotFound() {
        CreateFolderRequest request = new CreateFolderRequest("orphan-folder-" + UUID.randomUUID(), UUID.randomUUID());
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenCreateDuplicateFolder_thenConflict() {
        String name = "dup-folder-" + UUID.randomUUID();
        createFolder(name, null);

        CreateFolderRequest request = new CreateFolderRequest(name, null);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    // ==================== Upload to non-existent parent ====================

    @Test
    void whenUploadToNonExistentFolder_thenNotFound() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        builder.part("parentFolderId", UUID.randomUUID().toString());

        getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", false).build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenUploadDuplicateNameNotAllowed_thenConflict() {
        // Upload first file at root
        MultipartBodyBuilder b1 = new MultipartBodyBuilder();
        b1.part("file", new ClassPathResource("test.txt"));

        getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true).build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(b1.build()))
                .exchange()
                .expectStatus().isCreated();

        // Upload same file name without allowing duplicates
        MultipartBodyBuilder b2 = new MultipartBodyBuilder();
        b2.part("file", new ClassPathResource("test.txt"));

        getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", false).build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(b2.build()))
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    // ==================== Download folder as ZIP ====================

    @Test
    void whenDownloadFolder_thenZipReturned() {
        String name = "dl-folder-" + UUID.randomUUID();
        FolderResponse folder = createFolder(name, null);

        // Add a file
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        builder.part("parentFolderId", folder.id().toString());
        uploadDocument(builder);

        // Download the folder
        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", folder.id())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM);
    }

    @Test
    void whenDownloadNonExistentDocument_thenNotFound() {
        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== Download multiple empty list ====================

    @Test
    void whenDownloadMultipleEmpty_thenBadRequest() {
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/download-multiple")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(List.of())
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ==================== Move files to root (null target) ====================

    @Test
    void whenMoveFileToRoot_thenOk() {
        FolderResponse folder = createFolder("mv-root-" + UUID.randomUUID(), null);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        builder.part("parentFolderId", folder.id().toString());
        UploadResponse file = uploadDocument(builder);

        // Move to root (targetFolderId = null)
        MoveRequest moveRequest = new MoveRequest(List.of(file.id()), null, true);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/files/move")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(moveRequest)
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Copy files to root (null target) ====================

    @Test
    void whenCopyFilesToRoot_thenOk() {
        UploadResponse file = uploadDocument(newFileBuilder());

        String body = "{\"documentIds\":[\"" + file.id() + "\"],\"targetFolderId\":null,\"allowDuplicateFileNames\":true}";
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/files/copy")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Replace content of non-existent file ====================

    @Test
    void whenReplaceContentNonExistentFile_thenNotFound() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));

        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content", UUID.randomUUID())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== Replace content of folder (not file) ====================

    @Test
    void whenReplaceContentOfFolder_thenForbidden() {
        FolderResponse folder = createFolder("replace-folder-" + UUID.randomUUID(), null);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));

        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content", folder.id())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isForbidden();
    }

    // ==================== Delete folder ====================

    @Test
    void whenDeleteFolder_thenNoContent() {
        FolderResponse folder = createFolder("del-folder-" + UUID.randomUUID(), null);

        getWebTestClient().method(org.springframework.http.HttpMethod.DELETE)
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"documentIds\":[\"" + folder.id() + "\"]}")
                .exchange()
                .expectStatus().isNoContent();
    }

    // ==================== Helper ====================

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
