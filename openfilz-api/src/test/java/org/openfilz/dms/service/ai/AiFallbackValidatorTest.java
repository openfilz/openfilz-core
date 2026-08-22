package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.enums.AiProvider;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Startup validation of the fallback chain.
 * <p>
 * The failure this guards against is quiet: a chain entry naming a provider with no API key looks
 * fine until the day the primary runs out of quota — exactly when the fallback was supposed to
 * save the request. These tests pin that it is caught at boot instead.
 */
class AiFallbackValidatorTest {

    private AiProperties properties;
    private MockEnvironment environment;
    private AiFallbackValidator validator;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        properties.setActive(true);
        properties.getFallback().setEnabled(true);
        environment = new MockEnvironment();
        environment.setProperty("spring.ai.model.chat", "google-genai");
        validator = new AiFallbackValidator(properties, environment);
    }

    private void chain(String... entries) {
        properties.getFallback().setChain(List.of(entries));
    }

    private void keys(AiProvider provider, String... apiKeys) {
        Map<AiProvider, List<String>> pools = new LinkedHashMap<>(properties.getFallback().getKeys());
        pools.put(provider, List.of(apiKeys));
        properties.getFallback().setKeys(pools);
    }

    private void validate() {
        validator.run(null);
    }

    @Test
    @DisplayName("a fully configured chain starts cleanly")
    void acceptsAConfiguredChain() {
        chain("google:gemini-3.6-flash", "anthropic:claude-haiku-4-5");
        keys(AiProvider.GOOGLE, "AIza-one", "AIza-two");
        keys(AiProvider.ANTHROPIC, "sk-ant-one");

        assertThatCode(this::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a provider's single server key satisfies the check — a pool is not required")
    void acceptsTheSingleServerKey() {
        chain("google:gemini-3.6-flash");
        environment.setProperty("spring.ai.google.genai.api-key", "AIza-single");

        assertThatCode(this::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a chain entry with no key anywhere fails startup, naming the variable to set")
    void rejectsAProviderWithoutAnyKey() {
        chain("google:gemini-3.6-flash", "anthropic:claude-haiku-4-5");
        keys(AiProvider.GOOGLE, "AIza-one");
        // Anthropic has neither a pool nor a server key.

        assertThatThrownBy(this::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTHROPIC")
                .hasMessageContaining("AI_FALLBACK_KEYS_ANTHROPIC")
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    @DisplayName("the 'disabled' sentinel does not count as a configured key")
    void rejectsTheDisabledSentinel() {
        chain("google:gemini-3.6-flash");
        environment.setProperty("spring.ai.google.genai.api-key", "disabled");

        assertThatThrownBy(this::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("validation=WARN starts anyway, and says the chain is shorter than configured")
    void warnModeStartsAnyway() {
        chain("google:gemini-3.6-flash");
        properties.getFallback().setValidation(AiProperties.Fallback.Validation.WARN);

        assertThatCode(this::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a malformed entry is a startup error, not a silent omission")
    void rejectsMalformedEntries() {
        chain("google:gemini-3.6-flash", "no-separator");
        keys(AiProvider.GOOGLE, "AIza-one");

        assertThatThrownBy(this::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-separator");
    }

    @Test
    @DisplayName("openai-compatible without a base URL is rejected")
    void rejectsOpenAiCompatibleWithoutBaseUrl() {
        chain("openai-compatible:mistral-small");
        keys(AiProvider.OPENAI_COMPATIBLE, "sk-key");

        assertThatThrownBy(this::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_BASE_URL");
    }

    @Test
    @DisplayName("openai-compatible with a base URL is accepted")
    void acceptsOpenAiCompatibleWithBaseUrl() {
        chain("openai-compatible:mistral-small");
        keys(AiProvider.OPENAI_COMPATIBLE, "sk-key");
        environment.setProperty("spring.ai.openai.base-url", "https://api.mistral.ai");

        assertThatCode(this::validate).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- when validation must stay quiet

    @Test
    @DisplayName("nothing is validated when the AI feature is off")
    void skipsWhenAiIsInactive() {
        properties.setActive(false);
        chain("google:gemini-3.6-flash");   // deliberately keyless

        assertThatCode(this::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("nothing is validated when failover is off")
    void skipsWhenFailoverIsDisabled() {
        properties.getFallback().setEnabled(false);
        chain("google:gemini-3.6-flash");   // deliberately keyless

        assertThatCode(this::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an empty chain is reported but not fatal — there is simply nothing to check")
    void toleratesAnEmptyChain() {
        assertThatCode(this::validate).doesNotThrowAnyException();
    }

    /**
     * A local LLM is deployed so document content stays in-house; failing over would send RAG
     * context to a third-party API. The chain is ignored rather than validated — and a keyless
     * chain must therefore not block startup.
     */
    @Test
    @DisplayName("with Ollama as the chat provider the chain is ignored, not validated")
    void ignoresTheChainWhenOllamaIsTheChatProvider() {
        environment.setProperty("spring.ai.model.chat", "ollama");
        chain("google:gemini-3.6-flash");   // keyless: would fail if it were validated

        assertThatCode(this::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the validator never has to touch the chain bean, so AI-off deployments still boot")
    void dependsOnConfigurationOnly() {
        // AiFallbackChain needs a ChatModel bean, which does not exist when AI is switched off.
        // Constructing the validator from configuration alone is what keeps that true.
        assertThat(new AiFallbackValidator(new AiProperties(), new MockEnvironment())).isNotNull();
    }
}
