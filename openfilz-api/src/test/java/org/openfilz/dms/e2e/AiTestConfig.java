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

    @Bean
    @Primary
    public EmbeddingModel testEmbeddingModel() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        // Return a 768-dimensional unit vector for any embedding request (non-zero to avoid norm issues)
        float[] unitVector = new float[768];
        java.util.Arrays.fill(unitVector, 1.0f / 768);
        when(embeddingModel.embed(any(String.class))).thenReturn(unitVector);
        when(embeddingModel.embed(any(org.springframework.ai.document.Document.class))).thenReturn(unitVector);
        when(embeddingModel.dimensions()).thenReturn(768);

        var embedding = new Embedding(unitVector, 0);
        var embeddingResponse = new EmbeddingResponse(List.of(embedding));
        when(embeddingModel.call(any(org.springframework.ai.embedding.EmbeddingRequest.class)))
                .thenReturn(embeddingResponse);

        return embeddingModel;
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
                    String answer = prompt.getContents().contains("malformed")
                            ? "Sorry, I cannot produce that."
                            : """
                            ```json
                            {"category": "Report", "summary": "A short test summary of the document.",
                             "keywords": ["test", "report"], "language": "en", "entities": {"client": "ACME"}}
                            ```""";
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
