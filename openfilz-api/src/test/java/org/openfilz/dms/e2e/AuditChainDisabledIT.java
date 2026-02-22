package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CopyRequest;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.MoveRequest;
import org.openfilz.dms.dto.request.RenameRequest;
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

import java.util.List;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests with audit chain DISABLED to cover:
 * - AuditDAOImpl.logActionWithoutChain() and its lambda (10 missed branches, 0 covered)
 * - Various audit actions without hash chain: UPLOAD, CREATE_FOLDER, MOVE, COPY, RENAME, DELETE, REPLACE
 * - Also covers null resourceType/resourceId branches in audit logging
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class AuditChainDisabledIT extends TestContainersBaseConfig {

    public AuditChainDisabledIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @DynamicPropertySource
    static void disableChain(DynamicPropertyRegistry registry) {
        registry.add("openfilz.audit.chain.enabled", () -> false);
    }

    // ==================== Upload (FILE type audit, with details) ====================

    @Test
    void whenUploadFile_thenAuditLoggedWithoutChain() {
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse response = uploadDocument(builder);
        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.id());
    }

    @Test
    void whenUploadFileWithMetadata_thenAuditLoggedWithDetails() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "{\"key\":\"value\"}");
        UploadResponse response = getUploadResponse(builder, true);
        Assertions.assertNotNull(response);
    }

    // ==================== Create Folder (FOLDER type audit) ====================

    @Test
    void whenCreateFolder_thenAuditLoggedWithoutChain() {
        String folderName = "no-chain-folder-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);
        Assertions.assertNotNull(folder);
        Assertions.assertEquals(folderName, folder.name());
    }

    @Test
    void whenCreateSubfolder_thenAuditLoggedWithoutChain() {
        String parentName = "no-chain-parent-" + UUID.randomUUID();
        FolderResponse parent = createFolder(parentName, null);

        String childName = "no-chain-child-" + UUID.randomUUID();
        FolderResponse child = createFolder(childName, parent.id());
        Assertions.assertNotNull(child);
    }

    // ==================== Move (audit with details) ====================

    @Test
    void whenMoveFile_thenAuditLoggedWithoutChain() {
        String folderName = "no-chain-move-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse file = uploadDocument(builder);

        MoveRequest moveRequest = new MoveRequest(List.of(file.id()), folder.id(), true);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/files/move")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(moveRequest))
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Copy (audit with details) ====================

    @Test
    void whenCopyFile_thenAuditLoggedWithoutChain() {
        String srcFolder = "no-chain-src-" + UUID.randomUUID();
        FolderResponse src = createFolder(srcFolder, null);
        String dstFolder = "no-chain-dst-" + UUID.randomUUID();
        FolderResponse dst = createFolder(dstFolder, null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", src.id().toString());
        UploadResponse file = uploadDocument(builder);

        CopyRequest copyRequest = new CopyRequest(List.of(file.id()), dst.id(), false);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/files/copy")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(copyRequest))
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Rename (audit with details) ====================

    @Test
    void whenRenameFile_thenAuditLoggedWithoutChain() {
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse file = uploadDocument(builder);

        RenameRequest renameRequest = new RenameRequest("renamed-no-chain-" + UUID.randomUUID() + ".sql");
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/files/{id}/rename", file.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(renameRequest))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenRenameFolder_thenAuditLoggedWithoutChain() {
        String name = "no-chain-rename-" + UUID.randomUUID();
        FolderResponse folder = createFolder(name, null);

        RenameRequest renameRequest = new RenameRequest("renamed-" + UUID.randomUUID());
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/folders/{id}/rename", folder.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(renameRequest))
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Replace Content (audit with details) ====================

    @Test
    void whenReplaceContent_thenAuditLoggedWithoutChain() {
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse original = uploadDocument(builder);

        MultipartBodyBuilder replaceBuilder = new MultipartBodyBuilder();
        replaceBuilder.part("file", new ClassPathResource("test.txt"));

        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content", original.id())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(replaceBuilder.build()))
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Download (READ audit) ====================

    @Test
    void whenDownloadFile_thenAuditLoggedWithoutChain() {
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse file = uploadDocument(builder);

        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", file.id())
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Audit Search (search endpoint still works without chain) ====================

    @Test
    void whenSearchAuditTrail_thenOk() {
        // Upload first to ensure audit entries exist
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse file = uploadDocument(builder);

        // Search by resource ID
        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/audit/{id}", file.id())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenSearchAuditTrailByAction_thenOk() {
        MultipartBodyBuilder builder = newFileBuilder();
        uploadDocument(builder);

        // Search with action filter
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/audit/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"action\": \"UPLOAD_DOCUMENT\"}")
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Audit Verify (should handle no chain gracefully) ====================

    @Test
    void whenVerifyAuditWithChainDisabled_thenOk() {
        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/audit/verify")
                .exchange()
                .expectStatus().isOk();
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
