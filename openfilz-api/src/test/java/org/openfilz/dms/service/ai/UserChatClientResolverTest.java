package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.entity.UserAiSettings;
import org.openfilz.dms.enums.AiModelSource;
import org.openfilz.dms.enums.AiProvider;
import org.openfilz.dms.exception.AiNoModelException;
import org.openfilz.dms.repository.UserAiSettingsRepository;
import org.openfilz.dms.service.ai.UserChatClientResolver.ResolvedChat;
import org.openfilz.dms.service.impl.AiSettingsCipher;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

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
import static org.mockito.Mockito.when;

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
                .isInstanceOf(AiNoModelException.class)
                .hasMessageContaining("No chat model is configured")
                .hasMessageContaining("prototype/learned");
        assertThat(resolver.hasDefaultChatModel()).isFalse();
        assertThat(resolver.defaultModelSource()).isEqualTo(AiModelSource.NONE);
        assertThat(resolver.deploymentDefault()).isEmpty();
    }

    // ── personal settings and the runtime model source ──────────────────────

    private final UserAiSettingsRepository repository = mock(UserAiSettingsRepository.class);
    private final AiSettingsCipher cipher = mock(AiSettingsCipher.class);

    /** A runtime source an administrator controls; {@code version} moves on every change. */
    private static final class FakeSource implements DefaultChatModelSource {
        private volatile ResolvedChat model;
        private final AtomicLong version = new AtomicLong();

        void set(ResolvedChat model) {
            this.model = model;
            version.incrementAndGet();
        }

        @Override
        public Optional<ResolvedChat> current() {
            return Optional.ofNullable(model);
        }

        @Override
        public long version() {
            return version.get();
        }
    }

    /** BYOK on, over the given bean provider and runtime source. */
    private UserChatClientResolver byokResolver(org.springframework.beans.factory.ObjectProvider<ChatModel> provider,
                                                DefaultChatModelSource source) {
        return spy(new UserChatClientResolver(provider, mock(ToolCallingManager.class),
                repository, cipher, environment, true, source));
    }

    private void personalRow(String email) {
        UserAiSettings row = UserAiSettings.builder().userEmail(email).provider(AiProvider.ANTHROPIC.name())
                .model("claude-haiku-4-5").apiKeyEncrypted("cipher").build();
        when(repository.findById(email)).thenReturn(Mono.just(row));
        when(repository.existsById(email)).thenReturn(Mono.just(true));
        when(cipher.decrypt("cipher")).thenReturn("personal-key");
    }

    private void noPersonalRow(String email) {
        when(repository.findById(email)).thenReturn(Mono.empty());
        when(repository.existsById(email)).thenReturn(Mono.just(false));
    }

    @Test
    @DisplayName("personal settings + no server model at all -> the personal model, the default is never asked")
    void personalRowWorksWithoutAServerModel() {
        environment.setProperty("spring.ai.model.chat", "none");
        personalRow("me@example.com");
        UserChatClientResolver resolver = byokResolver(TestChatModelProvider.none(), new NoDefaultChatModelSource());
        doReturn(builtModel).when(resolver).buildChatModel(any(), any(), any(), any());

        ResolvedChat resolved = resolver.resolve("me@example.com").block();

        assertThat(resolved).isNotNull();
        assertThat(resolved.chatModel()).isSameAs(builtModel);
        assertThat(resolved.provider()).isEqualTo("ANTHROPIC");
        assertThat(resolver.modelSourceFor("me@example.com").block()).isEqualTo(AiModelSource.PERSONAL);
    }

    @Test
    @DisplayName("BYOK on, no personal row, no model anywhere -> AiNoModelException that allows user keys")
    void noRowNoModelFailsWithUserKeysAllowed() {
        environment.setProperty("spring.ai.model.chat", "none");
        noPersonalRow("me@example.com");
        UserChatClientResolver resolver = byokResolver(TestChatModelProvider.none(), new NoDefaultChatModelSource());

        assertThatThrownBy(() -> resolver.resolve("me@example.com").block())
                .isInstanceOf(AiNoModelException.class)
                .satisfies(e -> assertThat(((AiNoModelException) e).isUserKeysAllowed()).isTrue());
        assertThat(resolver.modelSourceFor("me@example.com").block()).isEqualTo(AiModelSource.NONE);
    }

    @Test
    @DisplayName("a runtime source beats the deployment default; removing it falls back")
    void sourceBeatsDeploymentAndRemovalFallsBack() {
        ChatModel deploymentBean = mock(ChatModel.class);
        ChatModel organisation = mock(ChatModel.class);
        noPersonalRow("me@example.com");
        FakeSource source = new FakeSource();
        UserChatClientResolver resolver = byokResolver(TestChatModelProvider.of(deploymentBean), source);
        assertThat(resolver.defaultModelSource()).isEqualTo(AiModelSource.DEPLOYMENT);

        source.set(new ResolvedChat(organisation, "GOOGLE", "gemini-3.6-flash"));
        assertThat(resolver.resolve("me@example.com").block().chatModel()).isSameAs(organisation);
        assertThat(resolver.defaultModelSource()).isEqualTo(AiModelSource.ORGANISATION);
        assertThat(resolver.modelSourceFor("me@example.com").block()).isEqualTo(AiModelSource.ORGANISATION);
        // The deployment's own model stays visible on its own, whatever the source says.
        assertThat(resolver.deploymentDefault()).map(ResolvedChat::chatModel).contains(deploymentBean);
        long before = resolver.defaultModelVersion();

        source.set(null);
        assertThat(resolver.defaultModelVersion()).isNotEqualTo(before);
        assertThat(resolver.resolve("me@example.com").block().chatModel()).isSameAs(deploymentBean);
        assertThat(resolver.defaultModelSource()).isEqualTo(AiModelSource.DEPLOYMENT);
    }

    @Test
    @DisplayName("a runtime source serves a deployment with no model of its own; removing it leaves none")
    void sourceAloneAndRemovalLeavesNone() {
        environment.setProperty("spring.ai.model.chat", "none");
        noPersonalRow("me@example.com");
        ChatModel organisation = mock(ChatModel.class);
        FakeSource source = new FakeSource();
        UserChatClientResolver resolver = byokResolver(TestChatModelProvider.none(), source);

        source.set(new ResolvedChat(organisation, "GOOGLE", "gemini-3.6-flash"));
        assertThat(resolver.resolve("me@example.com").block().chatModel()).isSameAs(organisation);
        assertThat(resolver.hasDefaultChatModel()).isTrue();

        source.set(null);
        assertThatThrownBy(() -> resolver.resolve("me@example.com").block()).isInstanceOf(AiNoModelException.class);
        assertThat(resolver.hasDefaultChatModel()).isFalse();
    }

    @Test
    @DisplayName("personal settings beat the runtime source")
    void personalBeatsSource() {
        personalRow("me@example.com");
        FakeSource source = new FakeSource();
        source.set(new ResolvedChat(mock(ChatModel.class), "GOOGLE", "gemini-3.6-flash"));
        UserChatClientResolver resolver = byokResolver(TestChatModelProvider.of(mock(ChatModel.class)), source);
        doReturn(builtModel).when(resolver).buildChatModel(any(), any(), any(), any());

        assertThat(resolver.resolve("me@example.com").block().chatModel()).isSameAs(builtModel);
        assertThat(resolver.modelSourceFor("me@example.com").block()).isEqualTo(AiModelSource.PERSONAL);
    }

    @Test
    @DisplayName("BYOK off: the personal row is never read, the source answers")
    void byokOffIgnoresPersonalRows() {
        FakeSource source = new FakeSource();
        ChatModel organisation = mock(ChatModel.class);
        source.set(new ResolvedChat(organisation, "GOOGLE", "gemini-3.6-flash"));
        UserChatClientResolver resolver = spy(new UserChatClientResolver(TestChatModelProvider.none(),
                mock(ToolCallingManager.class), repository, cipher, environment, false, source));

        assertThat(resolver.resolve("me@example.com").block().chatModel()).isSameAs(organisation);
        assertThat(resolver.modelSourceFor("me@example.com").block()).isEqualTo(AiModelSource.ORGANISATION);
        verify(repository, never()).findById(any(String.class));
        verify(repository, never()).existsById(any(String.class));
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

    // ---------------------------------------------------------------- hasDefaultChatModel (Settings.aiChatActive)

    @Test
    @DisplayName("matching bean -> a chat model exists")
    void hasDefaultChatModel_trueWithAMatchingBean() {
        environment.setProperty("spring.ai.model.chat", "ollama");
        assertThat(resolver(mock(OllamaChatModel.class)).hasDefaultChatModel()).isTrue();
    }

    @Test
    @DisplayName("no bean and selector none -> no chat model")
    void hasDefaultChatModel_falseWithNoBean() {
        environment.setProperty("spring.ai.model.chat", "none");
        assertThat(resolver(TestChatModelProvider.none()).hasDefaultChatModel()).isFalse();
    }

    /**
     * The native image compiles the Ollama bean in at build time; a runtime selector of none (e.g.
     * OPENFILZ_AI_MODEL=openfilz-cloud:default) must not let that bean serve a chat nobody deployed.
     */
    @Test
    @DisplayName("selector none but a compiled-in Ollama bean -> no chat model, and resolve refuses")
    void selectorNone_ignoresACompiledInProviderBean() {
        environment.setProperty("spring.ai.model.chat", "none");
        UserChatClientResolver resolver = resolver(mock(OllamaChatModel.class));

        assertThat(resolver.hasDefaultChatModel()).isFalse();
        assertThatThrownBy(() -> resolver.resolve("user@example.com").block())
                .hasMessageContaining("No chat model is configured");
    }

    /** Mocked-model suites pin the selector to none; their unrecognised bean is still the model. */
    @Test
    @DisplayName("selector none with an unrecognised (mock) bean -> trusted as the chat model")
    void selectorNone_trustsAnUnrecognisedBean() {
        environment.setProperty("spring.ai.model.chat", "none");
        assertThat(resolver(mock(ChatModel.class)).hasDefaultChatModel()).isTrue();
    }

    @Test
    @DisplayName("selector names a cloud provider with no bean and no key -> no chat model")
    void hasDefaultChatModel_falseWhenTheSelectedProviderCannotBeBuilt() {
        environment.setProperty("spring.ai.model.chat", "google-genai");
        assertThat(resolver(TestChatModelProvider.none()).hasDefaultChatModel()).isFalse();
    }
}
