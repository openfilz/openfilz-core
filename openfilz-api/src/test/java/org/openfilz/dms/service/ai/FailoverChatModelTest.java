package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.enums.AiProvider;
import org.openfilz.dms.service.ai.UserChatClientResolver.ResolvedChat;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The MCP vision model resolves the caller's model and fails over like the chat assistant.
 * <p>
 * The first case replays the Demo EE incident (2026-10-13): a native image whose compiled
 * Ollama bean names a model nobody pulled ({@code llama3}), wearing the runtime selector's label,
 * with the real models in a Gemini fallback chain. {@code describeImage} over MCP called the bare
 * bean and surfaced the 404; through this model it is answered by the chain.
 */
class FailoverChatModelTest {

    private static final String USER = "user@openfilz.test";

    private static ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    @DisplayName("a primary answering 'model not found' fails over to the chain")
    void deadPrimaryFailsOverToTheChain() {
        ChatModel compiledOllama = mock(ChatModel.class);
        when(compiledOllama.call(any(Prompt.class)))
                .thenThrow(new NonTransientAiException("HTTP 404 - {\"error\":\"model 'llama3' not found\"}"));
        ResolvedChat primary = new ResolvedChat(compiledOllama, "google-genai", "", AiKeyRef.UNKNOWN);

        ChatModel gemini = mock(ChatModel.class);
        when(gemini.call(any(Prompt.class))).thenReturn(answer("a whiteboard with an org chart"));

        UserChatClientResolver resolver = mock(UserChatClientResolver.class);
        when(resolver.resolve(USER)).thenReturn(Mono.just(primary));
        when(resolver.buildChatModel(eq(AiProvider.GOOGLE), anyString(), any(), anyString())).thenReturn(gemini);

        AiProperties properties = new AiProperties();
        properties.getFallback().setEnabled(true);
        properties.getFallback().setChain(List.of("google:gemini-3.5-flash-lite"));
        properties.getFallback().setKeys(Map.of(AiProvider.GOOGLE, List.of("pool-key")));
        AiFallbackChain chain = new AiFallbackChain(properties, resolver, new MockEnvironment());

        ChatResponse response = new FailoverChatModel(resolver, chain, USER).call(new Prompt("describe"));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("a whiteboard with an org chart");
    }

    @Test
    @DisplayName("the caller's own model answers when it is healthy, and its failure propagates when nothing is left")
    void callerModelAnswersAndLastFailurePropagates() {
        ChatModel byok = mock(ChatModel.class);
        when(byok.call(any(Prompt.class))).thenReturn(answer("from the user's model"));
        UserChatClientResolver resolver = mock(UserChatClientResolver.class);
        when(resolver.resolve(USER)).thenReturn(Mono.just(new ResolvedChat(byok, "ANTHROPIC", "claude", AiKeyRef.of("k"))));
        AiFallbackChain chain = new AiFallbackChain(new AiProperties(), resolver, new MockEnvironment());

        assertThat(new FailoverChatModel(resolver, chain, USER).call(new Prompt("describe"))
                .getResult().getOutput().getText()).isEqualTo("from the user's model");

        when(byok.call(any(Prompt.class))).thenThrow(new NonTransientAiException("HTTP 404 - model not found"));
        assertThatThrownBy(() -> new FailoverChatModel(resolver, chain, USER).call(new Prompt("describe")))
                .isInstanceOf(NonTransientAiException.class)
                .hasMessageContaining("model not found");
    }
}
