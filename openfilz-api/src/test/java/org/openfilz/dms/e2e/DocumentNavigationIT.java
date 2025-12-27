package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.response.AncestorInfo;
import org.openfilz.dms.dto.response.DocumentPosition;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Integration tests for document navigation endpoints:
 * - GET /api/v1/documents/{documentId}/ancestors
 * - GET /api/v1/documents/{documentId}/position
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class DocumentNavigationIT extends TestContainersBaseConfig {

    public DocumentNavigationIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    // ==================== GET ANCESTORS TESTS ====================

    @Test
    void getAncestors_FileAtRootLevel_ShouldReturnEmptyList() {
        // Given: a file uploaded at root level
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse uploadResponse = uploadDocument(builder);
        assertNotNull(uploadResponse);

        // When: getting ancestors for the file
        List<AncestorInfo> ancestors = getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{documentId}/ancestors", uploadResponse.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<AncestorInfo>>() {})
                .returnResult().getResponseBody();

        // Then: the list should be empty (no ancestors for root-level file)
        assertNotNull(ancestors);
        assertTrue(ancestors.isEmpty(), "File at root level should have no ancestors");
    }

    @Test
    void getAncestors_FileInSingleFolder_ShouldReturnOneAncestor() {
        // Given: a folder and a file inside it
        String folderName = "test-folder-ancestors-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        UploadResponse uploadResponse = uploadDocument(builder);

        // When: getting ancestors for the file
        List<AncestorInfo> ancestors = getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{documentId}/ancestors", uploadResponse.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<AncestorInfo>>() {})
                .returnResult().getResponseBody();

        // Then: should return one ancestor (the parent folder)
        assertNotNull(ancestors);
        assertEquals(1, ancestors.size());
        assertEquals(folder.id(), ancestors.get(0).id());
        assertEquals(folderName, ancestors.get(0).name());
        assertEquals("FOLDER", ancestors.get(0).type());
    }

    @Test
    void getAncestors_FileInNestedFolders_ShouldReturnAllAncestorsInOrder() {
        // Given: a nested folder structure: grandparent -> parent -> file
        String grandparentName = "grandparent-" + UUID.randomUUID();
        String parentName = "parent-" + UUID.randomUUID();

        FolderResponse grandparent = createFolder(grandparentName, null);
        FolderResponse parent = createFolder(parentName, grandparent.id());

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", parent.id().toString());
        UploadResponse uploadResponse = uploadDocument(builder);

        // When: getting ancestors for the file
        List<AncestorInfo> ancestors = getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{documentId}/ancestors", uploadResponse.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<AncestorInfo>>() {})
                .returnResult().getResponseBody();

        // Then: should return two ancestors in order (grandparent first, then parent)
        assertNotNull(ancestors);
        assertEquals(2, ancestors.size());

        // First ancestor should be the grandparent (root to immediate parent order)
        assertEquals(grandparent.id(), ancestors.get(0).id());
        assertEquals(grandparentName, ancestors.get(0).name());

        // Second ancestor should be the parent
        assertEquals(parent.id(), ancestors.get(1).id());
        assertEquals(parentName, ancestors.get(1).name());
    }

    @Test
    void getAncestors_DeeplyNestedFile_ShouldReturnAllAncestors() {
        // Given: a deeply nested folder structure (4 levels)
        String level1Name = "level1-" + UUID.randomUUID();
        String level2Name = "level2-" + UUID.randomUUID();
        String level3Name = "level3-" + UUID.randomUUID();
        String level4Name = "level4-" + UUID.randomUUID();

        FolderResponse level1 = createFolder(level1Name, null);
        FolderResponse level2 = createFolder(level2Name, level1.id());
        FolderResponse level3 = createFolder(level3Name, level2.id());
        FolderResponse level4 = createFolder(level4Name, level3.id());

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", level4.id().toString());
        UploadResponse uploadResponse = uploadDocument(builder);

        // When: getting ancestors for the file
        List<AncestorInfo> ancestors = getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{documentId}/ancestors", uploadResponse.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<AncestorInfo>>() {})
                .returnResult().getResponseBody();

        // Then: should return 4 ancestors in order
        assertNotNull(ancestors);
        assertEquals(4, ancestors.size());
        assertEquals(level1.id(), ancestors.get(0).id());
        assertEquals(level2.id(), ancestors.get(1).id());
        assertEquals(level3.id(), ancestors.get(2).id());
        assertEquals(level4.id(), ancestors.get(3).id());
    }

    @Test
    void getAncestors_ForFolder_ShouldReturnParentFolders() {
        // Given: nested folders
        String parentName = "parent-folder-" + UUID.randomUUID();
        String childName = "child-folder-" + UUID.randomUUID();

        FolderResponse parent = createFolder(parentName, null);
        FolderResponse child = createFolder(childName, parent.id());

        // When: getting ancestors for the child folder
        List<AncestorInfo> ancestors = getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{documentId}/ancestors", child.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<AncestorInfo>>() {})
                .returnResult().getResponseBody();

        // Then: should return the parent folder as ancestor
        assertNotNull(ancestors);
        assertEquals(1, ancestors.size());
        assertEquals(parent.id(), ancestors.get(0).id());
        assertEquals(parentName, ancestors.get(0).name());
    }

    @Test
    void getAncestors_FolderAtRootLevel_ShouldReturnEmptyList() {
        // Given: a folder at root level
        String folderName = "root-folder-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        // When: getting ancestors for the folder
        List<AncestorInfo> ancestors = getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{documentId}/ancestors", folder.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<AncestorInfo>>() {})
                .returnResult().getResponseBody();

        // Then: should return empty list
        assertNotNull(ancestors);
        assertTrue(ancestors.isEmpty());
    }

    @Test
    void getAncestors_NonExistentDocument_ShouldReturnEmptyList() {
        // Given: a non-existent document ID
        UUID nonExistentId = UUID.randomUUID();

        // When: getting ancestors for non-existent document
        List<AncestorInfo> ancestors = getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{documentId}/ancestors", nonExistentId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<AncestorInfo>>() {})
                .returnResult().getResponseBody();

        // Then: should return empty list (no ancestors found)
        assertNotNull(ancestors);
        assertTrue(ancestors.isEmpty());
    }

    // ==================== GET POSITION TESTS ====================

    @Test
    void getPosition_FileAtRootLevel_ShouldReturnPosition() {
        // Given: a file at root level
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse uploadResponse = uploadDocument(builder);

        // When: getting position for the file
        DocumentPosition position = getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{documentId}/position", uploadResponse.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentPosition.class)
                .returnResult().getResponseBody();

        // Then: should return valid position info
        assertNotNull(position);
        assertEquals(uploadResponse.id(), position.documentId());
        assertNull(position.parentId(), "Root level file should have null parentId");
        assertTrue(position.position() >= 0, "Position should be non-negative");
        assertTrue(position.totalItems() > 0, "Total items should be positive");
    }

    @Test
    void getPosition_FileInFolder_ShouldReturnPositionWithParentId() {
        // Given: a folder with a file inside
        String folderName = "test-folder-position-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        UploadResponse uploadResponse = uploadDocument(builder);

        // When: getting position for the file
        DocumentPosition position = getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{documentId}/position", uploadResponse.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentPosition.class)
                .returnResult().getResponseBody();

        // Then: should return position with parent folder ID
        assertNotNull(position);
        assertEquals(uploadResponse.id(), position.documentId());
        assertEquals(folder.id(), position.parentId());
        assertEquals(0, position.position(), "Single file in folder should be at position 0");
        assertEquals(1, position.totalItems(), "Folder should have exactly 1 item");
    }

    @Test
    void getPosition_MultipleFilesInFolder_ShouldReturnCorrectPositions() {
        // Given: a folder with multiple files
        String folderName = "test-folder-multi-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        // Upload 3 files
        MultipartBodyBuilder builder1 = newFileBuilder("test.txt");
        builder1.part("parentFolderId", folder.id().toString());
        UploadResponse file1 = uploadDocument(builder1);

        MultipartBodyBuilder builder2 = newFileBuilder("test_file_1.sql");
        builder2.part("parentFolderId", folder.id().toString());
        UploadResponse file2 = uploadDocument(builder2);

        MultipartBodyBuilder builder3 = newFileBuilder("test.txt");
        builder3.part("parentFolderId", folder.id().toString());
        UploadResponse file3 = uploadDocument(builder3);

        // When: getting positions for all files
        DocumentPosition pos1 = getPosition(file1.id());
        DocumentPosition pos2 = getPosition(file2.id());
        DocumentPosition pos3 = getPosition(file3.id());

        // Then: all should have 3 total items and different positions
        assertEquals(3, pos1.totalItems());
        assertEquals(3, pos2.totalItems());
        assertEquals(3, pos3.totalItems());

        // Positions should be unique and within range
        assertTrue(pos1.position() >= 0 && pos1.position() < 3);
        assertTrue(pos2.position() >= 0 && pos2.position() < 3);
        assertTrue(pos3.position() >= 0 && pos3.position() < 3);
    }

    @Test
    void getPosition_WithCustomSortBy_ShouldRespectSortOrder() {
        // Given: a folder with files
        String folderName = "test-folder-sort-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        UploadResponse uploadResponse = uploadDocument(builder);

        // When: getting position with different sort options
        DocumentPosition positionByName = getWebTestClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/documents/{documentId}/position")
                        .queryParam("sortBy", "name")
                        .queryParam("sortOrder", "ASC")
                        .build(uploadResponse.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentPosition.class)
                .returnResult().getResponseBody();

        DocumentPosition positionByNameDesc = getWebTestClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/documents/{documentId}/position")
                        .queryParam("sortBy", "name")
                        .queryParam("sortOrder", "DESC")
                        .build(uploadResponse.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentPosition.class)
                .returnResult().getResponseBody();

        // Then: both should return valid positions
        assertNotNull(positionByName);
        assertNotNull(positionByNameDesc);
        assertEquals(uploadResponse.id(), positionByName.documentId());
        assertEquals(uploadResponse.id(), positionByNameDesc.documentId());
    }

    @Test
    void getPosition_FolderInFolder_ShouldReturnPosition() {
        // Given: a parent folder with a child folder and a file
        String parentName = "parent-" + UUID.randomUUID();
        String childName = "child-" + UUID.randomUUID();

        FolderResponse parent = createFolder(parentName, null);
        FolderResponse child = createFolder(childName, parent.id());

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", parent.id().toString());
        UploadResponse file = uploadDocument(builder);

        // When: getting position for the child folder
        DocumentPosition folderPosition = getPosition(child.id());

        // Then: folder should have a valid position
        assertNotNull(folderPosition);
        assertEquals(child.id(), folderPosition.documentId());
        assertEquals(parent.id(), folderPosition.parentId());
        assertEquals(2, folderPosition.totalItems(), "Parent should have folder + file");
    }

    @Test
    void getPosition_FoldersBeforeFiles_ShouldHaveLowerPositions() {
        // Given: a folder with both subfolders and files
        String parentName = "parent-sort-test-" + UUID.randomUUID();
        FolderResponse parent = createFolder(parentName, null);

        // Create a subfolder
        String subfolderName = "zz-subfolder-" + UUID.randomUUID(); // z prefix to be sorted last alphabetically
        FolderResponse subfolder = createFolder(subfolderName, parent.id());

        // Create a file
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", parent.id().toString());
        UploadResponse file = uploadDocument(builder);

        // When: getting positions
        DocumentPosition folderPosition = getPosition(subfolder.id());
        DocumentPosition filePosition = getPosition(file.id());

        // Then: folder should come before file (folders are sorted first)
        assertNotNull(folderPosition);
        assertNotNull(filePosition);
        assertTrue(folderPosition.position() < filePosition.position(),
                "Folders should be positioned before files");
    }

    @Test
    void getPosition_DefaultSortParameters_ShouldWork() {
        // Given: a file in a folder
        String folderName = "default-sort-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        UploadResponse uploadResponse = uploadDocument(builder);

        // When: getting position without sort parameters (uses defaults)
        DocumentPosition position = getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{documentId}/position", uploadResponse.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentPosition.class)
                .returnResult().getResponseBody();

        // Then: should work with default parameters
        assertNotNull(position);
        assertEquals(uploadResponse.id(), position.documentId());
    }

    @Test
    void getPosition_WithInvalidSortBy_ShouldFallbackToName() {
        // Given: a file
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse uploadResponse = uploadDocument(builder);

        // When: getting position with invalid sortBy parameter
        DocumentPosition position = getWebTestClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/documents/{documentId}/position")
                        .queryParam("sortBy", "invalid_field")
                        .queryParam("sortOrder", "ASC")
                        .build(uploadResponse.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentPosition.class)
                .returnResult().getResponseBody();

        // Then: should still return a valid position (fallback to name sort)
        assertNotNull(position);
        assertEquals(uploadResponse.id(), position.documentId());
    }

    // ==================== COMBINED TESTS ====================

    @Test
    void ancestorsAndPosition_ConsistentForSameDocument() {
        // Given: a nested folder structure with a file
        String grandparentName = "gp-" + UUID.randomUUID();
        String parentName = "p-" + UUID.randomUUID();

        FolderResponse grandparent = createFolder(grandparentName, null);
        FolderResponse parent = createFolder(parentName, grandparent.id());

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", parent.id().toString());
        UploadResponse uploadResponse = uploadDocument(builder);

        // When: getting both ancestors and position
        List<AncestorInfo> ancestors = getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{documentId}/ancestors", uploadResponse.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<AncestorInfo>>() {})
                .returnResult().getResponseBody();

        DocumentPosition position = getPosition(uploadResponse.id());

        // Then: the last ancestor should match the position's parentId
        assertNotNull(ancestors);
        assertNotNull(position);
        assertFalse(ancestors.isEmpty());

        AncestorInfo immediateParent = ancestors.get(ancestors.size() - 1);
        assertEquals(immediateParent.id(), position.parentId(),
                "Last ancestor should be the same as position's parentId");
    }

    // ==================== HELPER METHODS ====================

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

    private DocumentPosition getPosition(UUID documentId) {
        return getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{documentId}/position", documentId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentPosition.class)
                .returnResult().getResponseBody();
    }
}
