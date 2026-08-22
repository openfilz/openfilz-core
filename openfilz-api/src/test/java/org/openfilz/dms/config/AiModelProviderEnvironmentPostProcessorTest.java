package org.openfilz.dms.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring AI 2.0 replaced the per-provider {@code spring.ai.<provider>.<kind>.enabled} flags with the
 * {@code spring.ai.model.*} selectors, whose conditions match when the property is missing. These
 * tests pin the translation OpenFilz applies, so that {@code openfilz.ai.active} alone decides
 * whether a provider is auto-configured, and both can never be built at once.
 */
class AiModelProviderEnvironmentPostProcessorTest {

    private final AiModelProviderEnvironmentPostProcessor postProcessor = new AiModelProviderEnvironmentPostProcessor();

    private MockEnvironment process(String... pairs) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i < pairs.length; i += 2) {
            environment.setProperty(pairs[i], pairs[i + 1]);
        }
        postProcessor.postProcessEnvironment(environment, null);
        return environment;
    }

    @Test
    void featureInactive_selectsNone() {
        MockEnvironment environment = process();

        assertEquals("none", environment.getProperty("spring.ai.model.chat"));
        assertEquals("none", environment.getProperty("spring.ai.model.embedding"));
    }

    /** Every AI bean is conditional on the feature flag, so a provider would be built for nothing. */
    @Test
    void featureInactive_ignoresProviderSwitches() {
        MockEnvironment environment = process(
                "openfilz.ai.ollama.chat.enabled", "true",
                "openfilz.ai.openai.embedding.enabled", "true");

        assertEquals("none", environment.getProperty("spring.ai.model.chat"));
        assertEquals("none", environment.getProperty("spring.ai.model.embedding"));
    }

    /** The single-switch case: Ollama's defaults already target a stock local install. */
    @Test
    void featureActiveWithoutProviderSwitch_fallsBackToOllama() {
        MockEnvironment environment = process("openfilz.ai.active", "true");

        assertEquals("ollama", environment.getProperty("spring.ai.model.chat"));
        assertEquals("ollama", environment.getProperty("spring.ai.model.embedding"));
    }

    @Test
    void ollamaEnabled_selectsOllama() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.ollama.chat.enabled", "true",
                "openfilz.ai.ollama.embedding.enabled", "true");

        assertEquals("ollama", environment.getProperty("spring.ai.model.chat"));
        assertEquals("ollama", environment.getProperty("spring.ai.model.embedding"));
    }

    @Test
    void openaiEnabled_selectsOpenai() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.openai.chat.enabled", "true",
                "openfilz.ai.openai.embedding.enabled", "true");

        assertEquals("openai", environment.getProperty("spring.ai.model.chat"));
        assertEquals("openai", environment.getProperty("spring.ai.model.embedding"));
    }

    @Test
    void anthropicEnabled_selectsAnthropicChat() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.anthropic.chat.enabled", "true");

        assertEquals("anthropic", environment.getProperty("spring.ai.model.chat"));
        // Anthropic has no embeddings API — embedding falls back to Ollama
        assertEquals("ollama", environment.getProperty("spring.ai.model.embedding"));
    }

    @Test
    void googleEnabled_selectsGoogleGenaiChat() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.google.chat.enabled", "true",
                "openfilz.ai.openai.embedding.enabled", "true");

        assertEquals("google-genai", environment.getProperty("spring.ai.model.chat"));
        assertEquals("openai", environment.getProperty("spring.ai.model.embedding"));
    }

    /** Embedding is Ollama/OpenAI only: the pgvector schema is pinned to their 768-dim output. */
    @Test
    void anthropicAndGoogleEmbeddingSwitches_areIgnored() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.anthropic.embedding.enabled", "true",
                "openfilz.ai.google.embedding.enabled", "true");

        assertEquals("ollama", environment.getProperty("spring.ai.model.embedding"));
    }

    @Test
    void chatPriority_anthropicBeatsGoogleAndOpenai() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.anthropic.chat.enabled", "true",
                "openfilz.ai.google.chat.enabled", "true",
                "openfilz.ai.openai.chat.enabled", "true");

        assertEquals("anthropic", environment.getProperty("spring.ai.model.chat"));
    }

    @Test
    void chatPriority_ollamaBeatsAnthropic() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.ollama.chat.enabled", "true",
                "openfilz.ai.anthropic.chat.enabled", "true");

        assertEquals("ollama", environment.getProperty("spring.ai.model.chat"));
    }

    @Test
    void chatPriority_googleBeatsOpenai() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.google.chat.enabled", "true",
                "openfilz.ai.openai.chat.enabled", "true");

        assertEquals("google-genai", environment.getProperty("spring.ai.model.chat"));
    }

    @Test
    void chatAndEmbeddingResolveIndependently() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.openai.chat.enabled", "true",
                "openfilz.ai.ollama.embedding.enabled", "true");

        assertEquals("openai", environment.getProperty("spring.ai.model.chat"));
        assertEquals("ollama", environment.getProperty("spring.ai.model.embedding"));
    }

    @Test
    void bothProvidersEnabled_ollamaWins() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.ollama.chat.enabled", "true",
                "openfilz.ai.openai.chat.enabled", "true");

        assertEquals("ollama", environment.getProperty("spring.ai.model.chat"));
    }

    @Test
    void explicitSelector_isNotOverridden() {
        MockEnvironment environment = process(
                "spring.ai.model.chat", "anthropic",
                "openfilz.ai.active", "true",
                "openfilz.ai.ollama.chat.enabled", "true");

        assertEquals("anthropic", environment.getProperty("spring.ai.model.chat"));
    }

    // ---------------------------------------------------------------- chain-derived chat provider

    /**
     * A provider listed in the fallback chain is already usable as a fallback (the chain builds
     * its clients programmatically, bypassing auto-configuration), so requiring a second switch
     * just to make it the primary was redundant. Setting a chain is enough.
     */
    @Test
    void fallbackChain_namesTheChatProviderWhenNoSwitchIsSet() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.fallback.enabled", "true",
                "openfilz.ai.fallback.chain", "google:gemini-3.6-flash,anthropic:claude-haiku-4-5");

        assertEquals("google-genai", environment.getProperty("spring.ai.model.chat"));
    }

    @Test
    void fallbackChain_isIgnoredWhenFailoverIsDisabled() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.fallback.chain", "anthropic:claude-haiku-4-5");

        assertEquals("ollama", environment.getProperty("spring.ai.model.chat"));
    }

    /** Existing deployments set switches; they must keep deciding, so nothing changes for them. */
    @Test
    void explicitSwitch_beatsTheFallbackChain() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.openai.chat.enabled", "true",
                "openfilz.ai.fallback.enabled", "true",
                "openfilz.ai.fallback.chain", "google:gemini-3.6-flash");

        assertEquals("openai", environment.getProperty("spring.ai.model.chat"));
    }

    @Test
    void explicitSelector_beatsTheFallbackChain() {
        MockEnvironment environment = process(
                "spring.ai.model.chat", "anthropic",
                "openfilz.ai.active", "true",
                "openfilz.ai.fallback.enabled", "true",
                "openfilz.ai.fallback.chain", "google:gemini-3.6-flash");

        assertEquals("anthropic", environment.getProperty("spring.ai.model.chat"));
    }

    /** An unusable leading entry must not strand the whole chain on Ollama. */
    @Test
    void fallbackChain_skipsEntriesItCannotRecognise() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.fallback.enabled", "true",
                "openfilz.ai.fallback.chain", "mystery:x,no-separator,anthropic:claude-haiku-4-5");

        assertEquals("anthropic", environment.getProperty("spring.ai.model.chat"));
    }

    @Test
    void fallbackChain_withNothingRecognisable_fallsBackToOllama() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.fallback.enabled", "true",
                "openfilz.ai.fallback.chain", "mystery:x");

        assertEquals("ollama", environment.getProperty("spring.ai.model.chat"));
    }

    /** Embedding is unaffected by the chain — it is chat-only configuration. */
    @Test
    void fallbackChain_doesNotAffectEmbedding() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.fallback.enabled", "true",
                "openfilz.ai.fallback.chain", "google:gemini-3.6-flash");

        assertEquals("ollama", environment.getProperty("spring.ai.model.embedding"));
    }

    @Test
    void unusedModelKinds_areDisabled() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.openai.chat.enabled", "true");

        assertEquals("none", environment.getProperty("spring.ai.model.image"));
        assertEquals("none", environment.getProperty("spring.ai.model.moderation"));
        assertEquals("none", environment.getProperty("spring.ai.model.audio.speech"));
        assertEquals("none", environment.getProperty("spring.ai.model.audio.transcription"));
    }

    /**
     * The switches live in application.yml, so this must run after config data is contributed —
     * ahead of it every switch reads as absent and the feature silently resolves to "none".
     */
    @Test
    void ordersAfterConfigDataIsContributed() {
        int ours = OrderUtils.getOrder(AiModelProviderEnvironmentPostProcessor.class, Ordered.LOWEST_PRECEDENCE);

        assertTrue(ours > ConfigDataEnvironmentPostProcessor.ORDER,
                "expected to run after ConfigDataEnvironmentPostProcessor");
    }
}
