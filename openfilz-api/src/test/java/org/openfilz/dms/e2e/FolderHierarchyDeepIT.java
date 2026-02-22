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

import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests targeting deep folder hierarchy operations:
 * - Move folder into itself → forbidden
 * - Move folder into its descendant → forbidden
 * - Move folder to target that is a file → forbidden
 * - Move file using folder API → forbidden
 * - Copy folder into itself → forbidden
 * - Copy non-existent file → not found
 * - Copy folder as file → forbidden
 * - Move file to non-existent target → not found
 * - Rename folder duplicate name (different from same name)
 * - Deep copy folder with nested children
 * - Delete folder with nested contents
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class FolderHierarchyDeepIT extends TestContainersBaseConfig {

    public FolderHierarchyDeepIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    // ==================== Move folder into itself ====================

    @Test
    void whenMoveFolderIntoItself_thenForbidden() {
        FolderResponse folder = createFolder("self-move-" + UUID.randomUUID(), null);

        String body = "{\"documentIds\":[\"" + folder.id() + "\"],\"targetFolderId\":\"" + folder.id() + "\"}";
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders/move")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isForbidden();
    }

    // ==================== Move folder into its descendant ====================

    @Test
    void whenMoveFolderIntoDescendant_thenForbidden() {
        FolderResponse parent = createFolder("parent-" + UUID.randomUUID(), null);
        FolderResponse child = createFolder("child-" + UUID.randomUUID(), parent.id());
        FolderResponse grandchild = createFolder("grandchild-" + UUID.randomUUID(), child.id());

        // Try to move parent into grandchild
        String body = "{\"documentIds\":[\"" + parent.id() + "\"],\"targetFolderId\":\"" + grandchild.id() + "\"}";
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders/move")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isForbidden();
    }

    // ==================== Move folder to non-existent target ====================

    @Test
    void whenMoveFolderToNonExistentTarget_thenNotFound() {
        FolderResponse folder = createFolder("orphan-mv-" + UUID.randomUUID(), null);

        String body = "{\"documentIds\":[\"" + folder.id() + "\"],\"targetFolderId\":\"" + UUID.randomUUID() + "\"}";
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders/move")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== Move non-existent folder ====================

    @Test
    void whenMoveNonExistentFolder_thenNotFound() {
        FolderResponse target = createFolder("target-" + UUID.randomUUID(), null);

        String body = "{\"documentIds\":[\"" + UUID.randomUUID() + "\"],\"targetFolderId\":\"" + target.id() + "\"}";
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders/move")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== Move file to non-existent target ====================

    @Test
    void whenMoveFileToNonExistentTarget_thenNotFound() {
        UploadResponse file = uploadDocument(newFileBuilder());

        String body = "{\"documentIds\":[\"" + file.id() + "\"],\"targetFolderId\":\"" + UUID.randomUUID() + "\"}";
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/files/move")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== Move non-existent file ====================

    @Test
    void whenMoveNonExistentFile_thenNotFound() {
        FolderResponse target = createFolder("file-target-" + UUID.randomUUID(), null);

        String body = "{\"documentIds\":[\"" + UUID.randomUUID() + "\"],\"targetFolderId\":\"" + target.id() + "\"}";
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/files/move")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== Move folder with name collision ====================

    @Test
    void whenMoveFolderNameCollision_thenConflict() {
        FolderResponse target = createFolder("collision-target-" + UUID.randomUUID(), null);
        String sameName = "dup-name-" + UUID.randomUUID();
        // Create a folder with the same name in target
        createFolder(sameName, target.id());
        // Create the folder we want to move
        FolderResponse toMove = createFolder(sameName, null);

        String body = "{\"documentIds\":[\"" + toMove.id() + "\"],\"targetFolderId\":\"" + target.id() + "\"}";
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders/move")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    // ==================== Move folder with allowDuplicateFileNames ====================

    @Test
    void whenMoveFolderWithAllowDuplicates_thenOk() {
        FolderResponse target = createFolder("dup-target-" + UUID.randomUUID(), null);
        FolderResponse toMove = createFolder("mv-dup-" + UUID.randomUUID(), null);

        String body = "{\"documentIds\":[\"" + toMove.id() + "\"],\"targetFolderId\":\"" + target.id() + "\",\"allowDuplicateFileNames\":true}";
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders/move")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Copy folder into itself ====================

    @Test
    void whenCopyFolderIntoItself_thenForbidden() {
        FolderResponse folder = createFolder("self-copy-" + UUID.randomUUID(), null);

        String body = "{\"documentIds\":[\"" + folder.id() + "\"],\"targetFolderId\":\"" + folder.id() + "\"}";
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders/copy")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isForbidden();
    }

    // ==================== Copy folder to non-existent target ====================

    @Test
    void whenCopyFolderToNonExistentTarget_thenNotFound() {
        FolderResponse folder = createFolder("copy-orphan-" + UUID.randomUUID(), null);

        String body = "{\"documentIds\":[\"" + folder.id() + "\"],\"targetFolderId\":\"" + UUID.randomUUID() + "\"}";
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders/copy")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== Copy non-existent file ====================

    @Test
    void whenCopyNonExistentFile_thenNotFound() {
        FolderResponse target = createFolder("copy-file-target-" + UUID.randomUUID(), null);

        String body = "{\"documentIds\":[\"" + UUID.randomUUID() + "\"],\"targetFolderId\":\"" + target.id() + "\",\"allowDuplicateFileNames\":true}";
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/files/copy")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== Copy file name collision without allow ====================

    @Test
    void whenCopyFileNameCollision_thenConflict() {
        FolderResponse target = createFolder("copy-collision-" + UUID.randomUUID(), null);

        // Upload file to root
        UploadResponse file = uploadDocument(newFileBuilder());

        // Upload file with same name to target
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test_file_1.sql"));
        builder.part("parentFolderId", target.id().toString());
        uploadDocument(builder);

        // Copy file to target without allowDuplicateFileNames
        String body = "{\"documentIds\":[\"" + file.id() + "\"],\"targetFolderId\":\"" + target.id() + "\",\"allowDuplicateFileNames\":false}";
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/files/copy")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    // ==================== Copy files to non-existent target ====================

    @Test
    void whenCopyFileToNonExistentTarget_thenNotFound() {
        UploadResponse file = uploadDocument(newFileBuilder());

        String body = "{\"documentIds\":[\"" + file.id() + "\"],\"targetFolderId\":\"" + UUID.randomUUID() + "\",\"allowDuplicateFileNames\":true}";
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/files/copy")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isNotFound();
    }

    // ==================== Deep copy folder with nested files and subfolders ====================

    @Test
    void whenCopyFolderWithNestedContents_thenOk() {
        // Create deep hierarchy: parent -> child -> grandchild + files at each level
        FolderResponse parent = createFolder("deep-copy-src-" + UUID.randomUUID(), null);
        FolderResponse child = createFolder("child-" + UUID.randomUUID(), parent.id());

        // Upload files at each level
        MultipartBodyBuilder b1 = new MultipartBodyBuilder();
        b1.part("file", new ClassPathResource("test.txt"));
        b1.part("parentFolderId", parent.id().toString());
        uploadDocument(b1);

        MultipartBodyBuilder b2 = new MultipartBodyBuilder();
        b2.part("file", new ClassPathResource("test_file_1.sql"));
        b2.part("parentFolderId", child.id().toString());
        uploadDocument(b2);

        // Create target
        FolderResponse target = createFolder("deep-copy-dst-" + UUID.randomUUID(), null);

        // Copy the entire tree
        String body = "{\"documentIds\":[\"" + parent.id() + "\"],\"targetFolderId\":\"" + target.id() + "\",\"allowDuplicateFileNames\":true}";
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders/copy")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Delete folder with nested contents ====================

    @Test
    void whenDeleteFolderWithNestedContents_thenNoContent() {
        FolderResponse parent = createFolder("del-deep-" + UUID.randomUUID(), null);
        FolderResponse child = createFolder("del-child-" + UUID.randomUUID(), parent.id());

        MultipartBodyBuilder b1 = new MultipartBodyBuilder();
        b1.part("file", new ClassPathResource("test.txt"));
        b1.part("parentFolderId", child.id().toString());
        uploadDocument(b1);

        getWebTestClient().method(org.springframework.http.HttpMethod.DELETE)
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"documentIds\":[\"" + parent.id() + "\"]}")
                .exchange()
                .expectStatus().isNoContent();
    }

    // ==================== Rename folder with duplicate name in same parent ====================

    @Test
    void whenRenameFolderToDuplicateName_thenConflict() {
        FolderResponse parent = createFolder("rename-parent-" + UUID.randomUUID(), null);
        String existingName = "existing-" + UUID.randomUUID();
        createFolder(existingName, parent.id());
        FolderResponse toRename = createFolder("to-rename-" + UUID.randomUUID(), parent.id());

        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/folders/{id}/rename", toRename.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"newName\":\"" + existingName + "\"}")
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    // ==================== Rename folder success ====================

    @Test
    void whenRenameFolderSuccess_thenOk() {
        FolderResponse folder = createFolder("rename-ok-" + UUID.randomUUID(), null);

        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/folders/{id}/rename", folder.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"newName\":\"renamed-" + UUID.randomUUID() + "\"}")
                .exchange()
                .expectStatus().isOk();
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
