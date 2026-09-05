package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.DocumentEmbeddingService;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Integration tests for DocumentEmbeddingService.
 * Tests embedding creation and removal with a SimpleVectorStore (in-memory).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
@Import(AiTestConfig.class)
public class DocumentEmbeddingServiceIT extends TestContainersBaseConfig {

    @Autowired
    private DocumentEmbeddingService documentEmbeddingService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private VectorStore vectorStore;

    public DocumentEmbeddingServiceIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void configureAiProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        registry.add("spring.ai.openai.api-key", () -> "test-dummy-key");
        // Spring AI 2.0 dropped the per-provider spring.ai.<provider>.<kind>.enabled flags: provider
        // auto-configuration is now gated on spring.ai.model.*, and those conditions match if the
        // property is missing. Pinning every selector to "none" keeps the real Ollama/OpenAI models
        // out of the context so AiTestConfig's mocks are the only ChatModel/EmbeddingModel beans.
        registry.add("spring.ai.model.chat", () -> "none");
        registry.add("spring.ai.model.embedding", () -> "none");
        registry.add("spring.ai.model.image", () -> "none");
        registry.add("spring.ai.model.moderation", () -> "none");
        registry.add("spring.ai.model.audio.speech", () -> "none");
        registry.add("spring.ai.model.audio.transcription", () -> "none");
        registry.add("spring.ai.vectorstore.pgvector.initialize-schema", () -> false);
        registry.add("spring.autoconfigure.exclude", () -> "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration");
    }

    // ========================= embedDocument =========================

    @Test
    void embedDocument_file_completesSuccessfully() {
        // Upload a text file first
        UploadResponse uploaded = uploadDocument(newFileBuilder("test.txt"));

        // Fetch the document entity
        Document document = documentRepository.findById(uploaded.id()).block();
        Assertions.assertNotNull(document);
        Assertions.assertEquals(DocumentType.FILE, document.getType());

        // Embed should complete without error
        StepVerifier.create(documentEmbeddingService.embedDocument(document))
                .verifyComplete();
    }

    @Test
    void embedDocument_folder_skipsEmbedding() {
        // Create a folder (type = FOLDER)
        Document folderDoc = Document.builder()
                .id(UUID.randomUUID())
                .name("test-folder")
                .type(DocumentType.FOLDER)
                .build();

        // Should complete immediately without doing anything
        StepVerifier.create(documentEmbeddingService.embedDocument(folderDoc))
                .verifyComplete();
    }

    @Test
    void embedDocument_sqlFile_completesSuccessfully() {
        // Upload a SQL file
        UploadResponse uploaded = uploadDocument(newFileBuilder());

        Document document = documentRepository.findById(uploaded.id()).block();
        Assertions.assertNotNull(document);

        StepVerifier.create(documentEmbeddingService.embedDocument(document))
                .verifyComplete();
    }

    @Test
    void embedDocument_multipleFiles_allComplete() {
        // Upload multiple files
        UploadResponse file1 = uploadDocument(newFileBuilder("test.txt"));
        UploadResponse file2 = uploadDocument(newFileBuilder("test_file_1.sql"));

        Document doc1 = documentRepository.findById(file1.id()).block();
        Document doc2 = documentRepository.findById(file2.id()).block();

        StepVerifier.create(documentEmbeddingService.embedDocument(doc1))
                .verifyComplete();
        StepVerifier.create(documentEmbeddingService.embedDocument(doc2))
                .verifyComplete();
    }

    // ========================= removeEmbeddings =========================

    @Test
    void removeEmbeddings_existingDocument_dropsItsChunks() {
        UploadResponse uploaded = uploadDocument(newFileBuilder("test.txt"));
        Document document = documentRepository.findById(uploaded.id()).block();
        documentEmbeddingService.embedDocument(document).block();
        Assertions.assertFalse(chunksOf(uploaded.id()).isEmpty(), "the document was embedded");

        StepVerifier.create(documentEmbeddingService.removeEmbeddings(uploaded.id()))
                .verifyComplete();

        Assertions.assertTrue(chunksOf(uploaded.id()).isEmpty(), "no chunk of the document is left");
    }

    @Test
    void embedDocument_twice_keepsOneSetOfChunks() {
        // A re-index (new version, replaced content) replaces the chunks instead of piling them up
        UploadResponse uploaded = uploadDocument(newFileBuilder("test.txt"));
        Document document = documentRepository.findById(uploaded.id()).block();
        documentEmbeddingService.embedDocument(document).block();
        int once = chunksOf(uploaded.id()).size();
        Assertions.assertTrue(once > 0);

        documentEmbeddingService.embedDocument(document).block();

        Assertions.assertEquals(once, chunksOf(uploaded.id()).size(), "the second embedding replaced the first");
    }

    @Test
    void hardDelete_removesTheChunks() {
        UploadResponse uploaded = uploadDocument(newFileBuilder("test.txt"));
        Document document = documentRepository.findById(uploaded.id()).block();
        documentEmbeddingService.embedDocument(document).block();
        Assertions.assertFalse(chunksOf(uploaded.id()).isEmpty(), "the document was embedded");

        // Soft delete is off in the tests, so this is the hard delete: the post-processor drops the chunks
        getWebTestClient().method(HttpMethod.DELETE)
                .uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FILES)
                .body(BodyInserters.fromValue(new DeleteRequest(List.of(uploaded.id()))))
                .exchange()
                .expectStatus().isNoContent();

        // The removal runs off the request thread
        for (int attempt = 0; attempt < 40 && !chunksOf(uploaded.id()).isEmpty(); attempt++) {
            sleep(250);
        }
        Assertions.assertTrue(chunksOf(uploaded.id()).isEmpty(), "the chunks of a hard-deleted document are gone");
    }

    /** Every chunk tagged with the document, whatever its similarity (the mock embeds everything alike). */
    private List<org.springframework.ai.document.Document> chunksOf(UUID documentId) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query("anything")
                .topK(1000)
                .similarityThreshold(0.0)
                .filterExpression("document_id == '" + documentId + "'")
                .build());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void removeEmbeddings_nonExistent_completesWithoutError() {
        // Should not throw even for a non-existent document ID
        StepVerifier.create(documentEmbeddingService.removeEmbeddings(UUID.randomUUID()))
                .verifyComplete();
    }

    // ========================= Embedding metadata enrichment =========================

    @Test
    void embedDocument_enrichesMetadata() {
        UploadResponse uploaded = uploadDocument(newFileBuilder("test.txt"));
        Document document = documentRepository.findById(uploaded.id()).block();

        // Embed the document
        documentEmbeddingService.embedDocument(document).block();

        // Search the vector store to verify metadata was added
        var searchRequest = SearchRequest.builder()
                .query("test content")
                .topK(10)
                .similarityThreshold(0.0) // Accept all results since we use mock embeddings
                .build();

        var results = vectorStore.similaritySearch(searchRequest);

        // With SimpleVectorStore + mock embeddings, we should find stored documents
        if (results != null && !results.isEmpty()) {
            var firstResult = results.getFirst();
            Assertions.assertTrue(firstResult.getMetadata().containsKey("document_id"),
                    "Metadata should contain document_id");
            Assertions.assertTrue(firstResult.getMetadata().containsKey("document_name"),
                    "Metadata should contain document_name");
            Assertions.assertTrue(firstResult.getMetadata().containsKey("content_type"),
                    "Metadata should contain content_type");
        }
    }

    // ========================= Error resilience =========================

    @Test
    void embedDocument_withCorruptStoragePath_doesNotThrow() {
        // Create a document with invalid storage path
        Document fakeDoc = Document.builder()
                .id(UUID.randomUUID())
                .name("corrupt-file.txt")
                .type(DocumentType.FILE)
                .storagePath("nonexistent/path/file.txt")
                .contentType("text/plain")
                .build();

        // Should complete without throwing (onErrorResume absorbs the error)
        StepVerifier.create(documentEmbeddingService.embedDocument(fakeDoc))
                .verifyComplete();
    }
}
