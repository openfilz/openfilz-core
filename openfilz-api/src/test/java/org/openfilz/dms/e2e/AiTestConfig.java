package org.openfilz.dms.e2e;

import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test configuration that provides mock Spring AI beans for integration testing.
 * This replaces the real ChatModel and EmbeddingModel so tests can run without
 * a real LLM (Ollama/OpenAI) or pgvector extension.
 */
@TestConfiguration
public class AiTestConfig {

    /**
     * A deterministic "embedding": a hashed bag of words in 768 dimensions, L2-normalised. Texts
     * sharing words are similar, texts sharing none are not, so the vector store, the neighbour
     * vote, the folder coherence and the prototype classifier behave as they would with a real
     * model — at zero cost and with no network. (The former constant vector made every document
     * equally similar to every other, which no filing test could tell apart.)
     */
    @Bean
    @Primary
    public EmbeddingModel testEmbeddingModel() {
        return new BagOfWordsEmbeddingModel();
    }

    public static final class BagOfWordsEmbeddingModel implements EmbeddingModel {
        static final int DIMENSIONS = 768;

        @Override
        public EmbeddingResponse call(org.springframework.ai.embedding.EmbeddingRequest request) {
            List<Embedding> out = new java.util.ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                out.add(new Embedding(embed(request.getInstructions().get(i)), i));
            }
            return new EmbeddingResponse(out);
        }

        @Override
        public float[] embed(org.springframework.ai.document.Document document) {
            return embed(document.getText());
        }

        @Override
        public float[] embed(String text) {
            float[] v = new float[DIMENSIONS];
            if (text != null) {
                for (String token : text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                    if (token.length() < 2) continue;
                    v[Math.floorMod(token.hashCode(), DIMENSIONS)] += 1;
                }
            }
            double norm = 0;
            for (float x : v) norm += x * x;
            if (norm == 0) {
                v[0] = 1;   // an empty text still has a direction
                return v;
            }
            float scale = (float) (1 / Math.sqrt(norm));
            for (int i = 0; i < v.length; i++) v[i] *= scale;
            return v;
        }

        @Override
        public int dimensions() {
            return DIMENSIONS;
        }
    }

    @Bean
    @Primary
    public VectorStore testVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    @Bean
    @Primary
    public ChatModel testChatModel() {
        ChatModel chatModel = mock(ChatModel.class);

        // Mock the streaming response
        var assistantMessage = new AssistantMessage("This is a test AI response about your documents.");
        var generation = new Generation(assistantMessage);
        var chatResponse = new ChatResponse(List.of(generation));

        // Spring AI 2.0's ChatClient copies the model's default options into every request
        // (DefaultChatClientUtils calls getOptions().mutate()), so the mock has to expose some.
        when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());

        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.just(chatResponse));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(chatResponse);
        // Tier-2 document insights: the enrichment prompt carries a marker; answer the JSON
        // contract — or garbage when the file name asks for it (DocumentInsightsTier2IT).
        when(chatModel.call(org.mockito.ArgumentMatchers.argThat((org.springframework.ai.chat.prompt.Prompt p) ->
                p != null && p.getContents() != null && p.getContents().contains("INSIGHTS_V1"))))
                .thenAnswer(invocation -> {
                    org.springframework.ai.chat.prompt.Prompt prompt = invocation.getArgument(0);
                    // The test invoices carry "Invoice F-…" in their text; everything else is a report,
                    // so the smart-filing suite can tell an invoice's neighbours from a report's.
                    String category = prompt.getContents().contains("Invoice F-") ? "Invoice"
                            : prompt.getContents().contains("Miscellaneous") ? "Other" : "Report";
                    String answer = prompt.getContents().contains("malformed")
                            ? "Sorry, I cannot produce that."
                            : """
                            ```json
                            {"category": "%s", "summary": "A short test summary of the document.",
                             "keywords": ["test", "report"], "language": "en", "entities": {"client": "ACME"}}
                            ```""".formatted(category);
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
                });
        // Smart filing (stage 2): the filing prompt carries its own marker; the mock proposes a
        // new folder with high confidence (AutoFileIT relies on it when no neighbours exist yet).
        when(chatModel.call(org.mockito.ArgumentMatchers.argThat((org.springframework.ai.chat.prompt.Prompt p) ->
                p != null && p.getContents() != null && p.getContents().contains("AUTOFILE_V1"))))
                .thenAnswer(invocation -> new ChatResponse(List.of(new Generation(new AssistantMessage("""
                        {"target": "Filed-by-model", "createFolders": ["Filed-by-model"], "confidence": 0.95,
                         "reason": "The mocked model files everything into Filed-by-model"}""")))));
        return chatModel;
    }

    @Bean
    @Primary
    public ChatClient testChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("You are a test AI assistant.")
                .build();
    }
}
