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
 * Tests AI tool functions against a real PostgreSQL database via Testcontainers.
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

    // ========================= queryDocuments =========================

    @Test
    void queryDocuments_rootFolder_returnsContents() {
        UploadResponse uploaded = uploadDocument(newFileBuilder());

        String result = documentAiTools.queryDocuments(null, null, null, null, null, null, null);
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.contains("Found"), "Should find documents");
        Assertions.assertTrue(result.contains("FILE") || result.contains("FOLDER"),
                "Should contain type markers");
    }

    @Test
    void queryDocuments_searchByName_returnsMatch() {
        UploadResponse uploaded = uploadDocument(newFileBuilder());

        String result = documentAiTools.queryDocuments(null, "test_file_1", null, null, null, null, null);
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.contains("test_file_1"), "Should find the uploaded file");
    }

    @Test
    void queryDocuments_searchByName_noMatch_returnsEmpty() {
        String result = documentAiTools.queryDocuments(null, "nonexistent_xyz_12345", null, null, null, null, null);
        Assertions.assertTrue(result.contains("No documents found"), "Should indicate no matches");
    }

    @Test
    void queryDocuments_filterByType_returnsFilesOnly() {
        uploadDocument(newFileBuilder());
        createFolder("type-filter-folder-" + UUID.randomUUID());

        String result = documentAiTools.queryDocuments(null, null, "FILE", null, null, null, null);
        Assertions.assertFalse(result.contains("[FOLDER]"), "Should not contain folders when filtering by FILE");
    }

    @Test
    void queryDocuments_sortByCreatedAt_returnsNewest() {
        uploadDocument(newFileBuilder());

        String result = documentAiTools.queryDocuments(null, null, "FILE", "createdAt", "DESC", 1, null);
        Assertions.assertTrue(result.contains("Found 1 result"), "Should return exactly 1 result");
    }

    @Test
    void queryDocuments_specificFolder_returnsContents() {
        FolderResponse folder = createFolder("query-folder-" + UUID.randomUUID());
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        uploadDocument(builder);

        String result = documentAiTools.queryDocuments(folder.id().toString(), null, null, null, null, null, null);
        Assertions.assertTrue(result.contains("Found"), "Should find documents in folder");
        Assertions.assertTrue(result.contains("FILE"), "Should contain the uploaded file");
    }

    @Test
    void queryDocuments_countOnly_returnsCount() {
        uploadDocument(newFileBuilder());

        String result = documentAiTools.queryDocuments(null, null, null, null, null, null, true);
        Assertions.assertTrue(result.contains("Found") && result.contains("document(s)"),
                "Should return count message, got: " + result);
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
