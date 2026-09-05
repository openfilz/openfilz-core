package org.openfilz.dms.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.EmbeddingModels;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.DocumentInsightView;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * The in-process embedding provider, end to end on the JVM: {@code TRANSFORMERS_EMBEDDING_ENABLED}
 * makes the API load nomic-embed-text-v1.5 through ONNX Runtime — no Ollama, no server — and
 * every embedding consumer runs on it: an upload is chunked and embedded into the vector store
 * (768-dimensional vectors), the prototype classifier names the document's kind from those
 * vectors, and the settings advertise nothing different. The chat model stays mocked; the
 * embedding model is the real one.
 * <p>
 * The model (~140 MB) and its tokenizer are downloaded from Hugging Face once into
 * {@code ~/.openfilz/onnx-cache} (CI caches that directory), so the first run needs the network.
 * The enterprise native image runs the same scenario against the container in
 * {@code EmbeddingOnnxNativeE2EIT}.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import({AiChatMockConfig.class, TransformersEmbeddingIT.RealEmbeddingVectorStore.class})
class TransformersEmbeddingIT extends TestContainersBaseConfig {

    private static final String DOCUMENTS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS;
    static final Path CACHE = Path.of(System.getProperty("user.home"), ".openfilz", "onnx-cache");

    /** The test Postgres has no pgvector: an in-memory store over the real in-process model replaces it. */
    @TestConfiguration
    static class RealEmbeddingVectorStore {
        @Bean
        @Primary
        VectorStore testVectorStore(EmbeddingModels models) {
            return SimpleVectorStore.builder(models.effective()).build();
        }
    }

    @Autowired
    private EmbeddingModels embeddingModels;

    @Autowired
    private VectorStore vectorStore;

    TransformersEmbeddingIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void configureAiProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        registry.add("openfilz.ai.transformers.embedding.enabled", () -> true);
        registry.add("openfilz.ai.transformers.embedding.cache-directory", () -> CACHE.toString());
        registry.add("openfilz.ai.insights.active", () -> true);
        registry.add("openfilz.ai.insights.classifier.mode", () -> "prototype");
        registry.add("spring.ai.openai.api-key", () -> "test-dummy-key");
        registry.add("spring.ai.model.chat", () -> "none");
        // The selector the environment post-processor would derive from the flag: no other provider is built
        registry.add("spring.ai.model.embedding", () -> "transformers");
        registry.add("spring.ai.model.image", () -> "none");
        registry.add("spring.ai.model.moderation", () -> "none");
        registry.add("spring.ai.model.audio.speech", () -> "none");
        registry.add("spring.ai.model.audio.transcription", () -> "none");
        registry.add("spring.ai.vectorstore.pgvector.initialize-schema", () -> false);
        registry.add("spring.autoconfigure.exclude", () -> "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration");
    }

    @Test
    @DisplayName("an upload is embedded in-process and classified by the real model, with no Ollama and no chat model")
    void embedsAndClassifiesInProcess() {
        assertThat(embeddingModels.inProcess()).isTrue();
        EmbeddingModel model = embeddingModels.effective();
        assertThat(model).isNotNull();
        assertThat(model.getClass().getSimpleName()).isEqualTo("TransformersEmbeddingModel");
        assertThat(model.dimensions()).isEqualTo(768);

        UploadResponse invoice = upload("facture-" + UUID.randomUUID() + ".txt",
                "INVOICE No F-2026-0042 from ACME SA to Globex. Description: consulting services. "
                        + "Subtotal 1 200.00 EUR, VAT 20 % 240.00 EUR, total due 1 440.00 EUR. Payment terms: 30 days net, bank transfer.");
        DocumentInsightView view = awaitInsights(invoice.id(), v -> "DONE".equals(v.status()) && v.tier() == 2);
        assertThat(view.category()).as(String.valueOf(view)).isEqualTo("invoice");
        assertThat(view.model()).isEqualTo("prototype:nomic-embed-text-v1.5");

        // The chunks went through the same model: real 768-dimensional vectors, findable by meaning
        List<org.springframework.ai.document.Document> chunks = awaitChunks(invoice.id());
        assertThat(chunks).isNotEmpty();
        List<org.springframework.ai.document.Document> byMeaning = vectorStore.similaritySearch(SearchRequest.builder()
                .query("a bill asking for payment of an amount with VAT").topK(1).build());
        assertThat(byMeaning).isNotEmpty();
        assertThat(byMeaning.getFirst().getMetadata().get("document_id")).isEqualTo(invoice.id().toString());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private List<org.springframework.ai.document.Document> awaitChunks(UUID documentId) {
        List<org.springframework.ai.document.Document> chunks = List.of();
        for (int attempt = 0; attempt < 120 && chunks.isEmpty(); attempt++) {
            chunks = vectorStore.similaritySearch(SearchRequest.builder().query("anything").topK(1000)
                    .similarityThreshold(0.0).filterExpression("document_id == '" + documentId + "'").build());
            if (chunks.isEmpty()) sleep(250);
        }
        return chunks;
    }

    private DocumentInsightView awaitInsights(UUID documentId, Predicate<DocumentInsightView> ready) {
        DocumentInsightView last = null;
        for (int attempt = 0; attempt < 480; attempt++) {
            var result = getWebTestClient().get().uri(DOCUMENTS + "/" + documentId + "/insights")
                    .exchange().returnResult(DocumentInsightView.class);
            if (result.getStatus().is2xxSuccessful()) {
                last = result.getResponseBody().blockFirst();
                if (last != null && ready.test(last)) {
                    return last;
                }
            }
            sleep(250);
        }
        throw new AssertionError("insights of " + documentId + " never became ready; last: " + last);
    }

    private UploadResponse upload(String name, String content) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return name;
            }
        }).contentType(MediaType.TEXT_PLAIN);
        return getWebTestClient().post().uri(DOCUMENTS + "/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange().expectStatus().isCreated().expectBody(UploadResponse.class).returnResult().getResponseBody();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
