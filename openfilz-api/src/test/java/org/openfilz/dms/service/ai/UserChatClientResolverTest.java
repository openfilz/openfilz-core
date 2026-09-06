package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.enums.AiProvider;
import org.openfilz.dms.repository.UserAiSettingsRepository;
import org.openfilz.dms.service.ai.UserChatClientResolver.ResolvedChat;
import org.openfilz.dms.service.impl.AiSettingsCipher;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The server-default primary handed out by {@link UserChatClientResolver#resolve}, and in
 * particular the native-image runtime switch: in the GraalVM image the auto-configured
 * {@code ChatModel} bean is fixed at AOT time (Ollama), so when the runtime
 * {@code spring.ai.model.chat} selector names a cloud provider, the primary must be rebuilt
 * programmatically — otherwise every chat request pays a failing Ollama call wearing the cloud
 * provider's name, and benches a healthy fallback-chain candidate with it.
 */
class UserChatClientResolverTest {

    private static final String GOOGLE_KEY = "google-server-key";

    private final MockEnvironment environment = new MockEnvironment();
    private final ChatModel builtModel = mock(ChatModel.class);

    /** Resolver on top of the given compiled-in default bean, BYOK off — the primary path. */
    private UserChatClientResolver resolver(ChatModel defaultBean) {
        return resolver(TestChatModelProvider.of(defaultBean));
    }

    /** Resolver over a possibly-absent bean — {@link TestChatModelProvider#none()} for no model at all. */
    private UserChatClientResolver resolver(org.springframework.beans.factory.ObjectProvider<ChatModel> provider) {
        return spy(new UserChatClientResolver(provider, mock(ToolCallingManager.class),
                mock(UserAiSettingsRepository.class), mock(AiSettingsCipher.class), environment, false));
    }

    /** The environment of a native image asked for Google at runtime while Ollama is baked in. */
    private void googleSelectedAtRuntime() {
        environment.setProperty("spring.ai.model.chat", "google-genai");
        environment.setProperty("spring.ai.google.genai.api-key", GOOGLE_KEY);
        environment.setProperty("spring.ai.google.genai.chat.model", "gemini-3.6-flash");
    }

    @Test
    @DisplayName("selector names google but the compiled bean is Ollama -> primary is rebuilt programmatically")
    void mismatchRebuildsPrimaryFromServerKey() {
        googleSelectedAtRuntime();
        UserChatClientResolver resolver = resolver(mock(OllamaChatModel.class));
        doReturn(builtModel).when(resolver).buildChatModel(any(), any(), any(), any());

        ResolvedChat resolved = resolver.resolve("user@example.com").block();

        assertThat(resolved).isNotNull();
        assertThat(resolved.chatModel()).isSameAs(builtModel);
        assertThat(resolved.provider()).isEqualTo("google-genai");
        assertThat(resolved.model()).isEqualTo("gemini-3.6-flash");
        assertThat(resolved.keyRef()).isEqualTo(AiKeyRef.of(GOOGLE_KEY));
        verify(resolver).buildChatModel(eq(AiProvider.GOOGLE), eq(GOOGLE_KEY), isNull(), eq("gemini-3.6-flash"));
    }

    @Test
    @DisplayName("the rebuilt primary is built once and reused across requests")
    void rebuiltPrimaryIsBuiltOnce() {
        googleSelectedAtRuntime();
        UserChatClientResolver resolver = resolver(mock(OllamaChatModel.class));
        doReturn(builtModel).when(resolver).buildChatModel(any(), any(), any(), any());

        ResolvedChat first = resolver.resolve("user@example.com").block();
        ResolvedChat second = resolver.resolve("other@example.com").block();

        assertThat(first.chatModel()).isSameAs(second.chatModel());
        verify(resolver, times(1)).buildChatModel(any(), any(), any(), any());
    }

    @Test
    @DisplayName("bean matching the selector is handed out untouched (every JVM deployment)")
    void matchingBeanIsKeptUntouched() {
        environment.setProperty("spring.ai.model.chat", "ollama");
        environment.setProperty("spring.ai.ollama.chat.model", "qwen2.5");
        ChatModel ollamaBean = mock(OllamaChatModel.class);
        UserChatClientResolver resolver = resolver(ollamaBean);

        ResolvedChat resolved = resolver.resolve("user@example.com").block();

        assertThat(resolved.chatModel()).isSameAs(ollamaBean);
        assertThat(resolved.provider()).isEqualTo("ollama");
        assertThat(resolved.model()).isEqualTo("qwen2.5");
        verify(resolver, never()).buildChatModel(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a bean of unrecognised class is trusted as-is, whatever the selector says")
    void unknownBeanClassIsTrusted() {
        googleSelectedAtRuntime();
        ChatModel unknownBean = mock(ChatModel.class);
        UserChatClientResolver resolver = resolver(unknownBean);

        ResolvedChat resolved = resolver.resolve("user@example.com").block();

        assertThat(resolved.chatModel()).isSameAs(unknownBean);
        verify(resolver, never()).buildChatModel(any(), any(), any(), any());
    }

    /**
     * A deployment with only pool keys ({@code AI_FALLBACK_KEYS_*}) has nothing to rebuild the
     * primary from — the baked bean stays, but the label MUST remain the selector's name: an
     * honest "ollama" label would trip the chain's data-residency rule and turn off the very
     * failover that carries this deployment.
     */
    @Test
    @DisplayName("no server API key -> compiled bean stays primary, still labelled with the selector")
    void missingServerKeyKeepsBeanAndSelectorLabel() {
        environment.setProperty("spring.ai.model.chat", "google-genai");
        environment.setProperty("spring.ai.google.genai.api-key", "disabled");   // application.yml sentinel
        environment.setProperty("spring.ai.google.genai.chat.model", "gemini-3.6-flash");
        ChatModel ollamaBean = mock(OllamaChatModel.class);
        UserChatClientResolver resolver = resolver(ollamaBean);

        ResolvedChat resolved = resolver.resolve("user@example.com").block();

        assertThat(resolved.chatModel()).isSameAs(ollamaBean);
        assertThat(resolved.provider()).isEqualTo("google-genai");
        assertThat(resolved.keyRef()).isEqualTo(AiKeyRef.UNKNOWN);
        verify(resolver, never()).buildChatModel(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a primary that refuses to build falls back to the compiled bean instead of failing the request")
    void buildFailureFallsBackToBean() {
        googleSelectedAtRuntime();
        ChatModel ollamaBean = mock(OllamaChatModel.class);
        UserChatClientResolver resolver = resolver(ollamaBean);
        doThrow(new IllegalStateException("client refused to build"))
                .when(resolver).buildChatModel(any(), any(), any(), any());

        ResolvedChat resolved = resolver.resolve("user@example.com").block();

        assertThat(resolved.chatModel()).isSameAs(ollamaBean);
        assertThat(resolved.provider()).isEqualTo("google-genai");
    }

    /**
     * The "light" profile: {@code spring.ai.model.chat=none} builds no {@code ChatModel} bean, and
     * the resolver must still be constructible — the insight and smart-filing services depend on it
     * even when their classifier never calls a model. Asking for one then fails per call, with a
     * message that names what needs a model and what does not.
     */
    @Test
    @DisplayName("no chat model bean at all -> the resolver builds, and only asking for a model fails")
    void absentBeanFailsOnlyWhenAModelIsActuallyAskedFor() {
        environment.setProperty("spring.ai.model.chat", "none");
        UserChatClientResolver resolver = resolver(TestChatModelProvider.none());

        assertThatThrownBy(() -> resolver.resolve("user@example.com").block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No chat model is configured")
                .hasMessageContaining("prototype/learned");
    }

    /**
     * The same absent bean, but the selector names a provider we can build and the server has its
     * key: that is a working chat configuration and must be built rather than refused.
     */
    @Test
    @DisplayName("no bean but a buildable selector + server key -> the primary is built programmatically")
    void absentBeanWithBuildableSelectorIsBuilt() {
        googleSelectedAtRuntime();
        UserChatClientResolver resolver = resolver(TestChatModelProvider.none());
        doReturn(builtModel).when(resolver).buildChatModel(any(), any(), any(), any());

        ResolvedChat resolved = resolver.resolve("user@example.com").block();

        assertThat(resolved).isNotNull();
        assertThat(resolved.chatModel()).isSameAs(builtModel);
        assertThat(resolved.provider()).isEqualTo("google-genai");
        verify(resolver).buildChatModel(eq(AiProvider.GOOGLE), eq(GOOGLE_KEY), isNull(), eq("gemini-3.6-flash"));
    }

    /**
     * The fallback chain is the application's retry mechanism: a spent quota must fail over to
     * the next candidate in seconds, not sit in Spring AI's default 10-retry exponential backoff
     * (up to 3 minutes per wait) — which is what turned every quota blip into a Cloudflare 524.
     */
    @Test
    @DisplayName("programmatically built Google models carry the short retry template, not Spring AI's default")
    void googleModelsUseShortRetryTemplate() {
        UserChatClientResolver resolver = resolver(mock(OllamaChatModel.class));

        ChatModel model = resolver.buildChatModel(AiProvider.GOOGLE, "test-key", null, "gemini-test");

        assertThat(ReflectionTestUtils.getField(model, "retryTemplate"))
                .isSameAs(UserChatClientResolver.SHORT_RETRY_TEMPLATE);
    }
}
