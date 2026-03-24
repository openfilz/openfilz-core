package org.openfilz.dms.e2e;

import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
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
        // Return a 768-dimensional zero vector for any embedding request
        float[] zeroVector = new float[768];
        when(embeddingModel.embed(any(String.class))).thenReturn(zeroVector);
        when(embeddingModel.embed(any(org.springframework.ai.document.Document.class))).thenReturn(zeroVector);
        when(embeddingModel.dimensions()).thenReturn(768);

        var embedding = new Embedding(zeroVector, 0);
        var embeddingResponse = new EmbeddingResponse(List.of(embedding));
        when(embeddingModel.call(any(org.springframework.ai.embedding.EmbeddingRequest.class)))
                .thenReturn(embeddingResponse);

        return embeddingModel;
    }

    @Bean
    @Primary
    public VectorStore testVectorStore(EmbeddingModel embeddingModel) {
        return new SimpleVectorStore(embeddingModel);
    }

    @Bean
    @Primary
    public ChatModel testChatModel() {
        ChatModel chatModel = mock(ChatModel.class);

        // Mock the streaming response
        var assistantMessage = new AssistantMessage("This is a test AI response about your documents.");
        var generation = new Generation(assistantMessage);
        var chatResponse = new ChatResponse(List.of(generation));

        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.just(chatResponse));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(chatResponse);

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
