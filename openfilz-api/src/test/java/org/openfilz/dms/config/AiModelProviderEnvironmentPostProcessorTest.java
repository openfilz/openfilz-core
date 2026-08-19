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
