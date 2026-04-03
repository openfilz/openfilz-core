package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.service.ai.DocumentAiTools;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
 * Integration tests for DocumentAiTools.
 * Tests all AI tool functions against a real PostgreSQL database via Testcontainers.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
@Import(AiTestConfig.class)
public class DocumentAiToolsIT extends TestContainersBaseConfig {

    @Autowired
    private DocumentAiTools documentAiTools;

    public DocumentAiToolsIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @DynamicPropertySource
    static void configureAiProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        registry.add("spring.ai.ollama.chat.enabled", () -> false);
        registry.add("spring.ai.ollama.embedding.enabled", () -> false);
        registry.add("spring.ai.openai.api-key", () -> "test-dummy-key");
        registry.add("spring.ai.openai.chat.enabled", () -> false);
        registry.add("spring.ai.openai.embedding.enabled", () -> false);
        registry.add("spring.ai.openai.image.enabled", () -> false);
        registry.add("spring.ai.openai.audio.speech.enabled", () -> false);
        registry.add("spring.ai.openai.audio.transcription.enabled", () -> false);
        registry.add("spring.ai.openai.moderation.enabled", () -> false);
        registry.add("spring.ai.vectorstore.pgvector.initialize-schema", () -> false);
        registry.add("spring.autoconfigure.exclude", () -> "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration");
    }

    // ========================= listFolder =========================

    @Test
    void listFolder_rootFolder_returnsContents() {
        // Upload a file to root
        UploadResponse uploaded = uploadDocument(newFileBuilder());

        String result = documentAiTools.listFolder(null);
        Assertions.assertNotNull(result);
        Assertions.assertNotEquals("The folder is empty.", result, "Root should have content");
        Assertions.assertTrue(result.contains("FILE") || result.contains("FOLDER"),
                "Should contain type markers");
    }

    @Test
    void listFolder_emptyFolder_returnsEmptyMessage() {
        // Create an empty folder
        FolderResponse folder = createFolder("empty-folder-" + UUID.randomUUID());

        String result = documentAiTools.listFolder(folder.id().toString());
        Assertions.assertEquals("The folder is empty.", result);
    }

    @Test
    void listFolder_withContents_returnsFilesAndFolders() {
        FolderResponse parentFolder = createFolder("parent-" + UUID.randomUUID());

        // Upload file into the folder
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", parentFolder.id().toString());
        uploadDocument(builder);

        // Create subfolder
        createSubFolder("sub-" + UUID.randomUUID(), parentFolder.id());

        String result = documentAiTools.listFolder(parentFolder.id().toString());
        Assertions.assertTrue(result.contains("FILE"), "Should list the file");
        Assertions.assertTrue(result.contains("FOLDER"), "Should list the subfolder");
    }

    @Test
    void listFolder_invalidUuid_returnsError() {
        String result = documentAiTools.listFolder("not-a-uuid");
        Assertions.assertTrue(result.startsWith("Error"), "Should return error for invalid UUID");
    }

    // ========================= searchByName =========================

    @Test
    void searchByName_existingFile_returnsMatch() {
        UploadResponse uploaded = uploadDocument(newFileBuilder());

        String result = documentAiTools.searchByName("test_file_1");
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.contains("test_file_1"), "Should find the uploaded file");
        Assertions.assertTrue(result.contains("FILE"), "Should indicate file type");
    }

    @Test
    void searchByName_nonExistent_returnsNoResults() {
        String result = documentAiTools.searchByName("nonexistent_file_xyz_12345");
        Assertions.assertTrue(result.contains("No documents found"), "Should indicate no matches");
    }

    @Test
    void searchByName_caseInsensitive_returnsMatch() {
        UploadResponse uploaded = uploadDocument(newFileBuilder());

        String result = documentAiTools.searchByName("TEST_FILE_1");
        Assertions.assertTrue(result.contains("test_file_1"),
                "Should find file regardless of case");
    }

    // ========================= getDocumentInfo =========================

    @Test
    void getDocumentInfo_file_returnsDetails() {
        UploadResponse uploaded = uploadDocument(newFileBuilder());

        String result = documentAiTools.getDocumentInfo(uploaded.id().toString());
        Assertions.assertTrue(result.contains("Name:"), "Should contain Name field");
        Assertions.assertTrue(result.contains("Type:"), "Should contain Type field");
        Assertions.assertTrue(result.contains("test_file_1.sql"), "Should contain file name");
    }

    @Test
    void getDocumentInfo_folder_returnsDetails() {
        FolderResponse folder = createFolder("info-folder-" + UUID.randomUUID());

        String result = documentAiTools.getDocumentInfo(folder.id().toString());
        Assertions.assertTrue(result.contains("Name:"), "Should contain Name field");
        Assertions.assertTrue(result.contains("FOLDER"), "Should indicate folder type");
    }

    @Test
    void getDocumentInfo_nonExistent_returnsNotFound() {
        String result = documentAiTools.getDocumentInfo(UUID.randomUUID().toString());
        Assertions.assertTrue(result.contains("not found") || result.contains("Error"),
                "Should indicate not found");
    }

    // ========================= createFolder =========================

    @Test
    void createFolder_atRoot_returnsSuccess() {
        String folderName = "ai-created-" + UUID.randomUUID();
        String result = documentAiTools.createFolder(folderName, null);

        Assertions.assertTrue(result.contains("created successfully"),
                "Should confirm creation, got: " + result);
        Assertions.assertTrue(result.contains(folderName));
    }

    @Test
    void createFolder_nested_returnsSuccess() {
        FolderResponse parent = createFolder("parent-for-ai-" + UUID.randomUUID());

        String nestedName = "nested-ai-" + UUID.randomUUID();
        String result = documentAiTools.createFolder(nestedName, parent.id().toString());

        Assertions.assertTrue(result.contains("created successfully"));
        Assertions.assertTrue(result.contains(nestedName));
    }

    // ========================= moveDocuments =========================

    @Test
    void moveDocuments_fileToFolder_returnsSuccess() {
        UploadResponse file = uploadDocument(newFileBuilder());
        FolderResponse targetFolder = createFolder("move-target-" + UUID.randomUUID());

        String result = documentAiTools.moveDocuments(
                file.id().toString(),
                targetFolder.id().toString());

        Assertions.assertTrue(result.contains("Successfully moved"),
                "Should confirm move, got: " + result);
        Assertions.assertTrue(result.contains("1 item(s)"));
    }

    @Test
    void moveDocuments_folderToFolder_returnsSuccess() {
        FolderResponse source = createFolder("move-source-" + UUID.randomUUID());
        FolderResponse target = createFolder("move-dest-" + UUID.randomUUID());

        String result = documentAiTools.moveDocuments(
                source.id().toString(),
                target.id().toString());

        Assertions.assertTrue(result.contains("Successfully moved"),
                "Should confirm folder move, got: " + result);
    }

    @Test
    void moveDocuments_invalidUuid_returnsError() {
        String result = documentAiTools.moveDocuments("bad-uuid", null);
        Assertions.assertTrue(result.startsWith("Error"), "Should return error for invalid UUID");
    }

    // ========================= renameDocument =========================

    @Test
    void renameDocument_file_returnsSuccess() {
        UploadResponse file = uploadDocument(newFileBuilder());
        String newName = "renamed-" + UUID.randomUUID() + ".sql";

        String result = documentAiTools.renameDocument(file.id().toString(), newName);
        Assertions.assertTrue(result.contains("Successfully renamed"),
                "Should confirm rename, got: " + result);
        Assertions.assertTrue(result.contains(newName));
    }

    @Test
    void renameDocument_folder_returnsSuccess() {
        FolderResponse folder = createFolder("rename-me-" + UUID.randomUUID());
        String newName = "renamed-folder-" + UUID.randomUUID();

        String result = documentAiTools.renameDocument(folder.id().toString(), newName);
        Assertions.assertTrue(result.contains("Successfully renamed"),
                "Should confirm folder rename, got: " + result);
    }

    @Test
    void renameDocument_nonExistent_returnsNotFound() {
        String result = documentAiTools.renameDocument(UUID.randomUUID().toString(), "new-name");
        Assertions.assertTrue(result.contains("not found") || result.contains("Error"),
                "Should indicate not found, got: " + result);
    }

    // ========================= countFolderElements =========================

    @Test
    void countFolderElements_emptyFolder_returnsZero() {
        FolderResponse folder = createFolder("count-empty-" + UUID.randomUUID());

        String result = documentAiTools.countFolderElements(folder.id().toString());
        Assertions.assertTrue(result.contains("0 item(s)"),
                "Empty folder should have 0 items, got: " + result);
    }

    @Test
    void countFolderElements_withContent_returnsCorrectCount() {
        FolderResponse folder = createFolder("count-folder-" + UUID.randomUUID());

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        uploadDocument(builder);

        String result = documentAiTools.countFolderElements(folder.id().toString());
        Assertions.assertTrue(result.contains("1 item(s)"),
                "Folder with 1 file should have 1 item, got: " + result);
    }

    // ========================= getDocumentPath =========================

    @Test
    void getDocumentPath_rootLevel_returnsRootMessage() {
        UploadResponse file = uploadDocument(newFileBuilder());

        String result = documentAiTools.getDocumentPath(file.id().toString());
        Assertions.assertTrue(result.contains("root level"),
                "Root-level file should say 'root level', got: " + result);
    }

    @Test
    void getDocumentPath_nested_returnsFullPath() {
        String parentName = "path-parent-" + UUID.randomUUID();
        FolderResponse parent = createFolder(parentName);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", parent.id().toString());
        UploadResponse file = uploadDocument(builder);

        String result = documentAiTools.getDocumentPath(file.id().toString());
        Assertions.assertTrue(result.contains("Path:"), "Should contain path prefix, got: " + result);
        Assertions.assertTrue(result.contains(parentName),
                "Path should include parent folder name, got: " + result);
    }

    @Test
    void getDocumentPath_deeplyNested_returnsAllAncestors() {
        String grandparentName = "gp-" + UUID.randomUUID();
        FolderResponse grandparent = createFolder(grandparentName);

        String parentName = "p-" + UUID.randomUUID();
        FolderResponse parent = createSubFolder(parentName, grandparent.id());

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", parent.id().toString());
        UploadResponse file = uploadDocument(builder);

        String result = documentAiTools.getDocumentPath(file.id().toString());
        Assertions.assertTrue(result.contains(grandparentName),
                "Should contain grandparent, got: " + result);
        Assertions.assertTrue(result.contains(parentName),
                "Should contain parent, got: " + result);
    }

    // ========================= Helpers =========================

    private FolderResponse createFolder(String name) {
        CreateFolderRequest request = new CreateFolderRequest(name, null);
        return getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();
    }

    private FolderResponse createSubFolder(String name, UUID parentId) {
        CreateFolderRequest request = new CreateFolderRequest(name, parentId);
        return getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();
    }
}
