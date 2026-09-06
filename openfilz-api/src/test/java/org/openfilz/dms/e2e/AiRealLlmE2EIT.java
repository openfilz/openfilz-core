package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.AiChatRequest;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.request.PageCriteria;
import org.openfilz.dms.dto.response.AiChatResponse;
import org.openfilz.dms.enums.SortOrder;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * End-to-end tests of the AI feature against a <b>real</b> LLM (see {@link SharedOllamaContainer}),
 * with no mocked {@code ChatModel} or {@code EmbeddingModel}. This is the counterpart to the
 * mock-based {@link AiChatControllerIT} and {@link DocumentAiToolsIT}: those pin the plumbing and
 * the tool implementations, but never exercise Spring AI's advisor chain, real embeddings, or real
 * tool dispatch driven by a model.
 * <p>
 * <b>On asserting against a language model.</b> Embeddings are deterministic, so the vector tests
 * assert exact outcomes. Generation is not: a 1.5B model occasionally skips a tool or answers from
 * memory. Those tests therefore assert on <em>side effects</em> (the folder really exists
 * afterwards) rather than on wording, and retry through {@link #eventually} so one unlucky sampling
 * doesn't fail the build. Assertions stay within what a small model honours reliably — demanding
 * exact phrasing would buy nothing and flake constantly.
 */
@Slf4j
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class AiRealLlmE2EIT extends TestContainersBaseConfig {

    private static final String AI_PREFIX = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_AI;

    /** CPU inference on a 1.5B model: generous, but bounded so a hang still fails the build. */
    private static final Duration LLM_TIMEOUT = Duration.ofMinutes(5);

    /** Samplings a generation-dependent assertion gets before it is called a failure. */
    private static final int LLM_ATTEMPTS = 3;

    private static final String LIST_FOLDER_QUERY = """
            query listFolder($request:ListFolderRequest!) {
                listFolder(request:$request) {
                  id
                  type
                  name
                }
            }
            """.trim();

    private HttpGraphQlClient graphQlClient;

    @Autowired
    protected VectorStore vectorStore;

    @Autowired
    protected EmbeddingModel embeddingModel;

    public AiRealLlmE2EIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void configureRealLlm(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        // AiModelProviderEnvironmentPostProcessor runs during prepareEnvironment, before
        // @DynamicPropertySource values exist, so it would read openfilz.ai.active as absent and
        // pin every selector to "none" — leaving no ChatModel to wire. Set the selectors directly;
        // an explicit spring.ai.model.* is the documented override and wins over the derived one.
        registry.add("spring.ai.model.chat", () -> "ollama");
        registry.add("spring.ai.model.embedding", () -> "ollama");
        registry.add("spring.ai.ollama.base-url", SharedOllamaContainer::getBaseUrl);
        registry.add("spring.ai.ollama.chat.model", () -> SharedOllamaContainer.CHAT_MODEL);
        registry.add("spring.ai.ollama.embedding.model", () -> SharedOllamaContainer.EMBEDDING_MODEL);
        // Small models wander under default sampling; pin the temperature for repeatability.
        registry.add("spring.ai.ollama.chat.temperature", () -> "0.0");
        // The container already holds both models (SharedOllamaContainer pulls them once).
        registry.add("spring.ai.ollama.init.pull-model-strategy", () -> "never");
        // A 1.5B model stops calling tools once the bound schema grows past the document + PDF
        // tools (it answers "I can't assist with that" or emits the call as text, ~2 min per
        // request on CI CPUs). This suite pins the pipeline, not the reorganisation / e-Sign
        // tools — those have their own ITs — so trim the chat surface the way a deployment on a
        // small model would (openfilz.ai.chat.excluded-contributors).
        registry.add("openfilz.ai.chat.excluded-contributors",
                () -> "OrganizeAiToolsContributor,SignatureAiToolsContributor,FilingAiToolsContributor,EmbeddingAiToolsContributor");
        // ...and the single tools added to DocumentAiTools since the 25-tool surface was measured
        registry.add("openfilz.ai.chat.excluded-tools", () -> "getDocumentActivity");
    }

    // ========================= Embeddings (deterministic) =========================

    @Test
    void embeddingModel_producesTheVectorWidthPgVectorIsConfiguredFor() {
        float[] vector = embeddingModel.embed("An invoice for consulting services.");

        Assertions.assertEquals(768, vector.length,
                "AiConfig builds PgVectorStore with dimensions(768); a mismatch corrupts the store");
        Assertions.assertTrue(hasNonZero(vector), "A real embedding must not be an all-zero vector");
    }

    @Test
    void embeddings_areSemantic_soUnrelatedTextIsFartherThanRelatedText() {
        float[] invoice = embeddingModel.embed("The invoice total is 4200 euros, payable in 30 days.");
        float[] billing = embeddingModel.embed("Payment terms and the amount due on this bill.");
        float[] unrelated = embeddingModel.embed("The migratory habits of arctic terns in winter.");

        double related = cosine(invoice, billing);
        double distant = cosine(invoice, unrelated);

        log.info("[AI-E2E] cosine(invoice,billing)={} cosine(invoice,terns)={}", related, distant);
        Assertions.assertTrue(related > distant,
                "A real embedding model must place billing text nearer an invoice than arctic terns");
    }

    @Test
    void vectorStore_retrievesTheTopicallyMatchingDocument() {
        String marker = "zeta" + UUID.randomUUID().toString().substring(0, 8);
        vectorStore.add(List.of(
                new Document(marker + " The quarterly revenue report shows growth in the EMEA region."),
                new Document(marker + " A recipe for sourdough bread using a rye starter.")));

        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query("How did sales perform in Europe?")
                .topK(1)
                .build());

        Assertions.assertNotNull(hits);
        Assertions.assertFalse(hits.isEmpty(), "Similarity search must return the seeded documents");
        Assertions.assertTrue(hits.getFirst().getText().contains("revenue"),
                "Semantic search should rank the revenue report above the bread recipe, got: "
                        + hits.getFirst().getText());
    }

    // ========================= Chat pipeline (real generation) =========================

    @Test
    void chat_streamsARealAnswerAndPersistsTheConversation() {
        List<AiChatResponse> events = chat("In one short sentence, what is a document management system?", null);

        Assertions.assertFalse(events.isEmpty(), "The SSE stream must carry events");
        Assertions.assertTrue(events.stream().anyMatch(e -> e.getType() == AiChatResponse.EventType.DONE),
                "The stream must terminate with a DONE event");

        String answer = textOf(events);
        Assertions.assertFalse(answer.isBlank(), "A real model must produce non-empty content");
        log.info("[AI-E2E] answer: {}", answer);

        UUID conversationId = conversationIdOf(events);
        Assertions.assertNotNull(conversationId, "The stream must report the conversation it created");
        Assertions.assertFalse(history(conversationId).isEmpty(), "The exchange must be persisted");
    }

    @Test
    void chat_continuesAnExistingConversation() {
        List<AiChatResponse> first = chat("Remember the number 42. Reply with just: ok", null);
        UUID conversationId = conversationIdOf(first);
        Assertions.assertNotNull(conversationId);

        chat("What number did I ask you to remember?", conversationId);

        // Both turns must land in the same conversation; the history endpoint is what the UI reads.
        Assertions.assertTrue(history(conversationId).size() >= 2,
                "Follow-up turns must append to the same conversation");
    }

    // ========================= Tool calling (real dispatch) =========================

    @Test
    void toolCalling_createFolder_reallyCreatesIt() {
        String folderName = "aimade" + UUID.randomUUID().toString().substring(0, 8);

        eventually("the assistant creates folder " + folderName,
                () -> {
                    chat("Create a folder named exactly " + folderName + " at the root.", null);
                    return folderExists(folderName);
                });
    }

    @Test
    void toolCalling_queryDocuments_findsAnUploadedFile() {
        String stem = "aiquery" + UUID.randomUUID().toString().substring(0, 8);
        uploadTextFile(stem + ".txt", "Nothing of consequence.");

        eventually("the assistant reports " + stem,
                () -> {
                    String answer = textOf(chat("Search the documents for a file whose name contains '"
                            + stem + "' and tell me its name.", null));
                    log.info("[AI-E2E] queryDocuments answer: {}", answer);
                    return answer.contains(stem);
                });
    }

    @Test
    void toolCalling_readDocumentContent_surfacesTheStoredText() {
        String fileName = "airead" + UUID.randomUUID().toString().substring(0, 8) + ".txt";
        String codeword = "pelican" + UUID.randomUUID().toString().substring(0, 6);
        uploadTextFile(fileName, "The agreed codeword is " + codeword + ".");

        eventually("the assistant reads " + fileName,
                () -> {
                    String answer = textOf(chat("Read the file named " + fileName
                            + " and tell me the codeword it contains.", null));
                    log.info("[AI-E2E] readDocumentContent answer: {}", answer);
                    return answer.contains(codeword);
                });
    }

    // ========================= Helpers =========================

    /**
     * Retries a generation-dependent expectation. A small model is sampled, not deterministic: one
     * refusal to call a tool is noise, three in a row is a defect.
     */
    private void eventually(String what, BooleanSupplier expectation) {
        for (int attempt = 1; attempt <= LLM_ATTEMPTS; attempt++) {
            if (expectation.getAsBoolean()) {
                return;
            }
            log.warn("[AI-E2E] attempt {}/{} did not satisfy: {}", attempt, LLM_ATTEMPTS, what);
        }
        throw new AssertionError("After " + LLM_ATTEMPTS + " samplings, " + what + " never happened");
    }

    private List<AiChatResponse> chat(String message, UUID conversationId) {
        AiChatRequest request = AiChatRequest.builder()
                .message(message)
                .conversationId(conversationId)
                .build();

        List<AiChatResponse> events = getWebTestClient().mutate()
                .responseTimeout(LLM_TIMEOUT)
                .build()
                .post()
                .uri(AI_PREFIX + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AiChatResponse.class)
                .getResponseBody()
                .collectList()
                .block(LLM_TIMEOUT);

        Assertions.assertNotNull(events, "The chat stream must not be empty");
        return events;
    }

    private List<AiChatResponse> history(UUID conversationId) {
        List<AiChatResponse> messages = getWebTestClient().get()
                .uri(AI_PREFIX + "/conversations/" + conversationId)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AiChatResponse.class)
                .getResponseBody()
                .collectList()
                .block(LLM_TIMEOUT);
        Assertions.assertNotNull(messages);
        return messages;
    }

    private String textOf(List<AiChatResponse> events) {
        StringBuilder text = new StringBuilder();
        events.stream()
                .map(AiChatResponse::getContent)
                .filter(Objects::nonNull)
                .forEach(text::append);
        return text.toString();
    }

    private UUID conversationIdOf(List<AiChatResponse> events) {
        return events.stream()
                .map(AiChatResponse::getConversationId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** Uploads a file whose name and body the test controls, so the model has something to find. */
    private void uploadTextFile(String fileName, String content) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)))
                .filename(fileName)
                .contentType(MediaType.TEXT_PLAIN);
        uploadDocument(builder);
    }

    /** Checks the folder through the read API rather than the tool that created it. */
    @SuppressWarnings("unchecked")
    private boolean folderExists(String name) {
        ListFolderRequest request = new ListFolderRequest(
                null, null, null, null, null, null, null, null, null, null, null, null,
                // pageSize is validated as < 100; the assistant only ever adds one folder per test.
                null, null, null, true, new PageCriteria("name", SortOrder.ASC, 1, 99), null);
        ClientGraphQlResponse response = graphQlClient().document(LIST_FOLDER_QUERY)
                .variable("request", request).execute().block();
        Assertions.assertNotNull(response);
        Assertions.assertTrue(response.getErrors().isEmpty(), () -> "GraphQL errors: " + response.getErrors());

        List<Map<String, Object>> items =
                (List<Map<String, Object>>) ((Map<String, Object>) response.getData()).get("listFolder");
        return items != null && items.stream().anyMatch(item -> name.equals(item.get("name")));
    }

    private HttpGraphQlClient graphQlClient() {
        if (graphQlClient == null) {
            graphQlClient = newGraphQlClient();
        }
        return graphQlClient;
    }

    private static boolean hasNonZero(float[] vector) {
        for (float v : vector) {
            if (v != 0f) {
                return true;
            }
        }
        return false;
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
