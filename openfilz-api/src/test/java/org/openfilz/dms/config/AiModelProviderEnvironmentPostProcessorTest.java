package org.openfilz.dms.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** Both OpenAI-shaped providers share one auto-configuration, hence one selector. */
    @Test
    void fallbackChain_mapsOpenAiCompatibleOntoTheOpenaiSelector() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.fallback.enabled", "true",
                "openfilz.ai.fallback.chain", "openai-compatible:mistral-small");

        assertEquals("openai", environment.getProperty("spring.ai.model.chat"));
    }

    /** Operators space out comma-separated lists; that must not silently disable the derivation. */
    @Test
    void fallbackChain_toleratesWhitespaceAroundEntries() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.fallback.enabled", "true",
                "openfilz.ai.fallback.chain", " google : gemini-3.6-flash , anthropic:claude-haiku-4-5 ");

        assertEquals("google-genai", environment.getProperty("spring.ai.model.chat"));
    }

    @Test
    void fallbackChain_blankChainFallsBackToOllama() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.fallback.enabled", "true",
                "openfilz.ai.fallback.chain", "  ");

        assertEquals("ollama", environment.getProperty("spring.ai.model.chat"));
    }

    /** The chain must not switch anything on while the feature itself is off. */
    @Test
    void fallbackChain_isIgnoredWhileAiIsInactive() {
        MockEnvironment environment = process(
                "openfilz.ai.fallback.enabled", "true",
                "openfilz.ai.fallback.chain", "google:gemini-3.6-flash");

        assertEquals("none", environment.getProperty("spring.ai.model.chat"));
    }

    // ---------------------------------------------------------------- chain-derived chat model

    /**
     * application.yml consults {@code openfilz-internal.ai.chat-model.<selector>} as a nested
     * placeholder default, so contributing it is what makes the chain authoritative over the
     * primary's model. A drifted key would silently do nothing, hence the exact-name assertions.
     */
    @Test
    void fallbackChain_suppliesThePrimaryModelForTheChosenProvider() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.fallback.enabled", "true",
                "openfilz.ai.fallback.chain", "google:gemini-2.0-flash,anthropic:claude-haiku-4-5");

        assertEquals("google-genai", environment.getProperty("spring.ai.model.chat"));
        assertEquals("gemini-2.0-flash",
                environment.getProperty("openfilz-internal.ai.chat-model.google-genai"));
        // Only the primary's model is derived; later entries are built by the chain itself.
        assertNull(environment.getProperty("openfilz-internal.ai.chat-model.anthropic"));
    }

    @Test
    void fallbackChain_derivesNoModelWhenASwitchPicksTheProvider() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.openai.chat.enabled", "true",
                "openfilz.ai.fallback.enabled", "true",
                "openfilz.ai.fallback.chain", "google:gemini-2.0-flash");

        assertEquals("openai", environment.getProperty("spring.ai.model.chat"));
        assertNull(environment.getProperty("openfilz-internal.ai.chat-model.google-genai"));
    }

    @Test
    void fallbackChain_skipsEntriesWithNoModel() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.fallback.enabled", "true",
                "openfilz.ai.fallback.chain", "google:,anthropic:claude-haiku-4-5");

        assertEquals("anthropic", environment.getProperty("spring.ai.model.chat"));
        assertEquals("claude-haiku-4-5",
                environment.getProperty("openfilz-internal.ai.chat-model.anthropic"));
    }

    @Test
    void fallbackChain_derivesNoModelWhileAiIsInactive() {
        MockEnvironment environment = process(
                "openfilz.ai.fallback.enabled", "true",
                "openfilz.ai.fallback.chain", "google:gemini-2.0-flash");

        assertNull(environment.getProperty("openfilz-internal.ai.chat-model.google-genai"));
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

    // ---------------------------------------------------------------- OPENFILZ_AI_MODEL + OPENFILZ_AI_API_KEY

    private static final String MODEL = "openfilz.ai.model";
    private static final String KEY = "openfilz.ai.api-key";

    @Test
    void model_google_namesChatProviderModelAndKey() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                MODEL, "google:gemini-3.6-flash",
                KEY, "AIza-test");

        assertEquals("google-genai", environment.getProperty("spring.ai.model.chat"));
        assertEquals("gemini-3.6-flash", environment.getProperty("openfilz-internal.ai.chat-model.google-genai"));
        assertEquals("AIza-test", environment.getProperty("openfilz-internal.ai.api-key.google-genai"));
        // Chat-only provider: embedding keeps its own resolution.
        assertEquals("ollama", environment.getProperty("spring.ai.model.embedding"));
        assertNull(environment.getProperty("openfilz-internal.ai.insights-model"));
        assertNull(environment.getProperty("openfilz-internal.ai.cloud-api-key"));
    }

    @Test
    void model_providerAliases_mapOntoTheSelectors() {
        String[][] cases = {
                {"gemini:g", "google-genai"}, {"google-genai:g", "google-genai"}, {"GOOGLE:g", "google-genai"},
                {"anthropic:c", "anthropic"}, {"claude:c", "anthropic"},
                {"openai:o", "openai"}, {"openai-compatible:o", "openai"},
                {"ollama:q", "ollama"},
        };
        for (String[] c : cases) {
            MockEnvironment environment = process("openfilz.ai.active", "true", MODEL, c[0], KEY, "k");
            assertEquals(c[1], environment.getProperty("spring.ai.model.chat"), c[0]);
            assertEquals(c[0].substring(c[0].indexOf(':') + 1),
                    environment.getProperty("openfilz-internal.ai.chat-model." + c[1]), c[0]);
        }
    }

    @Test
    void model_anthropic_derivesItsKey() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                MODEL, " claude : claude-haiku-4-5 ",
                KEY, " sk-ant ");

        assertEquals("anthropic", environment.getProperty("spring.ai.model.chat"));
        assertEquals("claude-haiku-4-5", environment.getProperty("openfilz-internal.ai.chat-model.anthropic"));
        assertEquals("sk-ant", environment.getProperty("openfilz-internal.ai.api-key.anthropic"));
    }

    @Test
    void model_openai_derivesItsKey() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                MODEL, "openai:gpt-4o-mini",
                KEY, "sk-openai");

        assertEquals("openai", environment.getProperty("spring.ai.model.chat"));
        assertEquals("gpt-4o-mini", environment.getProperty("openfilz-internal.ai.chat-model.openai"));
        assertEquals("sk-openai", environment.getProperty("openfilz-internal.ai.api-key.openai"));
    }

    /** Ollama needs no key; its tag keeps every colon after the provider. */
    @Test
    void model_ollama_keepsTheTagAndDerivesNoKey() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                MODEL, "ollama:qwen2.5:1.5b",
                KEY, "ignored");

        assertEquals("ollama", environment.getProperty("spring.ai.model.chat"));
        assertEquals("qwen2.5:1.5b", environment.getProperty("openfilz-internal.ai.chat-model.ollama"));
        assertNull(environment.getProperty("openfilz-internal.ai.api-key.ollama"));
    }

    @Test
    void model_withoutKey_derivesTheModelOnly() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                MODEL, "google:gemini-3.6-flash");

        assertEquals("google-genai", environment.getProperty("spring.ai.model.chat"));
        assertNull(environment.getProperty("openfilz-internal.ai.api-key.google-genai"));
    }

    /** Existing deployments set switches; a switch keeps deciding the provider. */
    @Test
    void explicitSwitch_beatsTheModel() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.openai.chat.enabled", "true",
                MODEL, "google:gemini-3.6-flash",
                KEY, "AIza-test");

        assertEquals("openai", environment.getProperty("spring.ai.model.chat"));
    }

    /** A switch naming the model's own provider picks up the model and key. */
    @Test
    void switchForTheSameProvider_usesTheModelAndKey() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.google.chat.enabled", "true",
                MODEL, "google:gemini-2.5-pro",
                KEY, "AIza-test");

        assertEquals("google-genai", environment.getProperty("spring.ai.model.chat"));
        assertEquals("gemini-2.5-pro", environment.getProperty("openfilz-internal.ai.chat-model.google-genai"));
        assertEquals("AIza-test", environment.getProperty("openfilz-internal.ai.api-key.google-genai"));
    }

    @Test
    void explicitSelector_beatsTheModel() {
        MockEnvironment environment = process(
                "spring.ai.model.chat", "anthropic",
                "openfilz.ai.active", "true",
                MODEL, "google:gemini-3.6-flash");

        assertEquals("anthropic", environment.getProperty("spring.ai.model.chat"));
    }

    /** The model names the primary; the chain still supplies the fallbacks, but no longer the primary's model. */
    @Test
    void model_beatsTheFallbackChainForThePrimary() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                MODEL, "anthropic:claude-haiku-4-5",
                KEY, "sk-ant",
                "openfilz.ai.fallback.enabled", "true",
                "openfilz.ai.fallback.chain", "google:gemini-2.0-flash,openai:gpt-4o-mini");

        assertEquals("anthropic", environment.getProperty("spring.ai.model.chat"));
        assertEquals("claude-haiku-4-5", environment.getProperty("openfilz-internal.ai.chat-model.anthropic"));
        assertNull(environment.getProperty("openfilz-internal.ai.chat-model.google-genai"));
    }

    /** The explicit vendor properties win through application.yml's nested placeholders; nothing is overridden here. */
    @Test
    void model_neverOverridesTheVendorProperties() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "spring.ai.google.genai.api-key", "AIza-explicit",
                "spring.ai.google.genai.chat.model", "gemini-explicit",
                MODEL, "google:gemini-3.6-flash",
                KEY, "AIza-pair");

        assertEquals("AIza-explicit", environment.getProperty("spring.ai.google.genai.api-key"));
        assertEquals("gemini-explicit", environment.getProperty("spring.ai.google.genai.chat.model"));
    }

    @Test
    void model_openfilzCloud_setsInsightsModelAndCloudKey_andChatNone() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                MODEL, "openfilz-cloud:default",
                KEY, "ofz-tenant");

        assertEquals("none", environment.getProperty("spring.ai.model.chat"));
        assertEquals("openfilz-cloud:default", environment.getProperty("openfilz-internal.ai.insights-model"));
        assertEquals("ofz-tenant", environment.getProperty("openfilz-internal.ai.cloud-api-key"));
        assertNull(environment.getProperty("openfilz-internal.ai.chat-model.openai"));
        assertNull(environment.getProperty("openfilz-internal.ai.api-key.openai"));
        // Embeddings are unaffected by the managed model.
        assertEquals("ollama", environment.getProperty("spring.ai.model.embedding"));
    }

    @Test
    void model_openfilzCloud_underscoreSpelling() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                MODEL, "openfilz_cloud:default");

        assertEquals("none", environment.getProperty("spring.ai.model.chat"));
        assertEquals("openfilz-cloud:default", environment.getProperty("openfilz-internal.ai.insights-model"));
        assertNull(environment.getProperty("openfilz-internal.ai.cloud-api-key"));
    }

    @Test
    void model_openfilzCloud_withAnExplicitChatSwitch_keepsTheSwitch() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                "openfilz.ai.anthropic.chat.enabled", "true",
                MODEL, "openfilz-cloud:default",
                KEY, "ofz-tenant");

        assertEquals("anthropic", environment.getProperty("spring.ai.model.chat"));
        assertEquals("openfilz-cloud:default", environment.getProperty("openfilz-internal.ai.insights-model"));
        // The pair's key belongs to the gateway, never to the vendor.
        assertNull(environment.getProperty("openfilz-internal.ai.api-key.anthropic"));
    }

    @Test
    void model_openfilzCloud_withAChain_takesTheChainsVendorEntryForChat() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                MODEL, "openfilz-cloud:default",
                "openfilz.ai.fallback.enabled", "true",
                "openfilz.ai.fallback.chain", "openfilz-cloud:default,google:gemini-3.6-flash");

        assertEquals("google-genai", environment.getProperty("spring.ai.model.chat"));
        assertEquals("gemini-3.6-flash", environment.getProperty("openfilz-internal.ai.chat-model.google-genai"));
    }

    @Test
    void model_openfilzCloud_withAChainOfManagedEntriesOnly_staysNone() {
        MockEnvironment environment = process(
                "openfilz.ai.active", "true",
                MODEL, "openfilz-cloud:default",
                "openfilz.ai.fallback.enabled", "true",
                "openfilz.ai.fallback.chain", "openfilz-cloud:default");

        assertEquals("none", environment.getProperty("spring.ai.model.chat"));
    }

    /** OPENFILZ_AI_ACTIVE stays the master switch: the model alone turns nothing on. */
    @Test
    void model_whileAiIsInactive_derivesNothing() {
        MockEnvironment environment = process(
                MODEL, "openfilz-cloud:default",
                KEY, "ofz-tenant");
        assertEquals("none", environment.getProperty("spring.ai.model.chat"));
        assertEquals("none", environment.getProperty("spring.ai.model.embedding"));
        assertNull(environment.getProperty("openfilz-internal.ai.insights-model"));
        assertNull(environment.getProperty("openfilz-internal.ai.cloud-api-key"));

        environment = process(MODEL, "google:gemini-3.6-flash", KEY, "AIza-test");
        assertEquals("none", environment.getProperty("spring.ai.model.chat"));
        assertNull(environment.getProperty("openfilz-internal.ai.chat-model.google-genai"));
        assertNull(environment.getProperty("openfilz-internal.ai.api-key.google-genai"));
    }

    /** A malformed value is ignored (warned about at startup), leaving the historical defaults. */
    @Test
    void model_malformed_isIgnored() {
        for (String bad : new String[]{"gemini-3.6-flash", "mystery:x", "google:", ":model", "  "}) {
            MockEnvironment environment = process(
                    "openfilz.ai.active", "true",
                    MODEL, bad,
                    KEY, "k");

            assertEquals("ollama", environment.getProperty("spring.ai.model.chat"), bad);
            assertNull(environment.getProperty("openfilz-internal.ai.api-key.google-genai"), bad);
            assertNull(environment.getProperty("openfilz-internal.ai.insights-model"), bad);
        }
    }

    /**
     * The derived properties only matter through application.yml's nested placeholders; a drifted key
     * on either side silently does nothing, so resolve the real file end to end.
     */
    private org.springframework.core.env.StandardEnvironment realYaml(String... pairs) throws java.io.IOException {
        org.springframework.core.env.StandardEnvironment environment = new org.springframework.core.env.StandardEnvironment();
        // The developer's own GOOGLE_API_KEY & co. must not leak into the assertions.
        environment.getPropertySources().remove(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(org.springframework.core.env.StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        java.util.Map<String, Object> overrides = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            overrides.put(pairs[i], pairs[i + 1]);
        }
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test", overrides));
        new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("application.yml", new org.springframework.core.io.ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        postProcessor.postProcessEnvironment(environment, null);
        return environment;
    }

    @Test
    void applicationYml_resolvesThePairIntoTheVendorProperties() throws Exception {
        var environment = realYaml(
                "openfilz.ai.active", "true",
                "OPENFILZ_AI_MODEL", "anthropic:claude-haiku-4-5",
                "OPENFILZ_AI_API_KEY", "sk-ant-pair");

        assertEquals("anthropic", environment.getProperty("spring.ai.model.chat"));
        assertEquals("claude-haiku-4-5", environment.getProperty("spring.ai.anthropic.chat.model"));
        assertEquals("sk-ant-pair", environment.getProperty("spring.ai.anthropic.api-key"));
        assertEquals("disabled", environment.getProperty("spring.ai.google.genai.api-key"));
    }

    @Test
    void applicationYml_explicitVendorVariablesWinOverThePair() throws Exception {
        var environment = realYaml(
                "openfilz.ai.active", "true",
                "OPENFILZ_AI_MODEL", "google:gemini-3.6-flash",
                "OPENFILZ_AI_API_KEY", "AIza-pair",
                "GOOGLE_API_KEY", "AIza-explicit",
                "GOOGLE_CHAT_MODEL", "gemini-explicit");

        assertEquals("AIza-explicit", environment.getProperty("spring.ai.google.genai.api-key"));
        assertEquals("gemini-explicit", environment.getProperty("spring.ai.google.genai.chat.model"));
    }

    @Test
    void applicationYml_ollamaModelComesFromThePair() throws Exception {
        var environment = realYaml(
                "openfilz.ai.active", "true",
                "OPENFILZ_AI_MODEL", "ollama:llama3.2:3b");

        assertEquals("llama3.2:3b", environment.getProperty("spring.ai.ollama.chat.model"));
    }

    @Test
    void applicationYml_openfilzCloudResolvesInsightsModelAndGatewayKey() throws Exception {
        var environment = realYaml(
                "openfilz.ai.active", "true",
                "OPENFILZ_AI_MODEL", "openfilz-cloud:default",
                "OPENFILZ_AI_API_KEY", "ofz-tenant");

        assertEquals("none", environment.getProperty("spring.ai.model.chat"));
        assertEquals("openfilz-cloud:default", environment.getProperty("openfilz.ai.insights.model"));
        assertEquals("ofz-tenant", environment.getProperty("openfilz.ai.cloud.api-key"));
        assertEquals("disabled", environment.getProperty("spring.ai.openai.api-key"));

        var explicit = realYaml(
                "openfilz.ai.active", "true",
                "OPENFILZ_AI_MODEL", "openfilz-cloud:default",
                "OPENFILZ_AI_API_KEY", "ofz-tenant",
                "OPENFILZ_AI_INSIGHTS_MODEL", "anthropic:claude-haiku-4-5",
                "OPENFILZ_AI_CLOUD_API_KEY", "ofz-explicit");
        assertEquals("anthropic:claude-haiku-4-5", explicit.getProperty("openfilz.ai.insights.model"));
        assertEquals("ofz-explicit", explicit.getProperty("openfilz.ai.cloud.api-key"));
    }

    @Test
    void invalidModelReason_namesTheProblem() {
        assertNull(AiModelProviderEnvironmentPostProcessor.invalidModelReason(null));
        assertNull(AiModelProviderEnvironmentPostProcessor.invalidModelReason(""));
        assertNull(AiModelProviderEnvironmentPostProcessor.invalidModelReason("google:gemini-3.6-flash"));
        assertNull(AiModelProviderEnvironmentPostProcessor.invalidModelReason("openfilz-cloud:default"));
        assertTrue(AiModelProviderEnvironmentPostProcessor.invalidModelReason("gemini").contains("provider:model"));
        assertTrue(AiModelProviderEnvironmentPostProcessor.invalidModelReason("mistral:small").contains("unknown provider 'mistral'"));
        assertTrue(AiModelProviderEnvironmentPostProcessor.invalidModelReason("google:").contains("no model"));
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
