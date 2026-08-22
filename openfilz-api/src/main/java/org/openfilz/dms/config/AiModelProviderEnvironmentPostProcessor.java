package org.openfilz.dms.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Derives Spring AI's model selectors from {@code openfilz.ai.active} and the per-provider
 * OpenFilz switches, so that {@code openfilz.ai.active} is the single switch for the feature.
 * <p>
 * Spring AI 2.0 removed the per-provider {@code spring.ai.<provider>.chat.enabled} flags. A
 * provider's auto-configuration is now gated on a single selector — {@code spring.ai.model.chat}
 * and {@code spring.ai.model.embedding} — whose value is the provider name, or {@code none} to
 * disable it. Those conditions are {@code matchIfMissing = true}, so leaving the selectors unset
 * with both the Ollama and OpenAI starters on the classpath would instantiate <em>both</em>
 * providers' models.
 * <p>
 * The rules applied here:
 * <ul>
 *   <li>{@code openfilz.ai.active=false} (default) — every selector is {@code none}. Nothing is
 *       built, which matches the rest of the feature: every AI bean is conditional on that flag.</li>
 *   <li>{@code openfilz.ai.active=true} — chat and embedding resolve independently from the
 *       {@code openfilz.ai.<provider>.<kind>.enabled} switches ({@code OLLAMA_CHAT_ENABLED},
 *       {@code ANTHROPIC_CHAT_ENABLED}, {@code GOOGLE_CHAT_ENABLED}, {@code OPENAI_CHAT_ENABLED}, …).
 *       Chat priority when several are enabled: Ollama &gt; Anthropic &gt; Google &gt; OpenAI;
 *       embedding is Ollama/OpenAI only (Anthropic has no embeddings API and the pgvector schema
 *       is pinned to 768 dims). When a kind has no switch set it falls back to Ollama, whose
 *       defaults point at a stock local install (localhost:11434, qwen2.5, nomic-embed-text) —
 *       so turning the feature on is enough.</li>
 *   <li>When no chat switch is set but {@code openfilz.ai.fallback.chain} is, the chain's first
 *       entry names the chat provider. A provider listed in the chain is already usable as a
 *       fallback — the chain builds its clients programmatically, bypassing auto-configuration —
 *       so requiring a second switch just to make the same provider the <em>primary</em> was
 *       redundant. Setting a chain is now enough.</li>
 *   <li>An explicitly-set {@code spring.ai.model.*} property always wins — the escape hatch for
 *       providers OpenFilz exposes no switch for.</li>
 * </ul>
 * Note that only the <em>provider</em> is derived from the chain, not the model: the primary's
 * model still comes from {@code <PROVIDER>_CHAT_MODEL}, which application.yml always defines (with
 * a default), so there is no way to tell "operator chose this" from "nobody set it". Keeping
 * chain[0]'s model equal to that value — the defaults already line up — makes the primary and the
 * first chain entry the same candidate, which is then de-duplicated.
 * The ordering matters: the switches read here come from {@code application.yml}, so this has to
 * run after {@code ConfigDataEnvironmentPostProcessor} has contributed the config data — otherwise
 * every switch would read as absent.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
public class AiModelProviderEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "openfilzAiModelProviders";

    private static final String AI_ACTIVE = "openfilz.ai.active";
    private static final String CHAT_SELECTOR = "spring.ai.model.chat";
    private static final String EMBEDDING_SELECTOR = "spring.ai.model.embedding";
    private static final String FALLBACK_ENABLED = "openfilz.ai.fallback.enabled";
    private static final String FALLBACK_CHAIN = "openfilz.ai.fallback.chain";

    /** Model kinds OpenFilz never uses; left enabled they would build clients we don't need. */
    private static final String[] UNUSED_SELECTORS = {
            "spring.ai.model.image",
            "spring.ai.model.moderation",
            "spring.ai.model.audio.speech",
            "spring.ai.model.audio.transcription"
    };

    private static final String NONE = "none";
    private static final String OLLAMA = "ollama";
    private static final String OPENAI = "openai";
    private static final String ANTHROPIC = "anthropic";
    private static final String GOOGLE_GENAI = "google-genai";

    /**
     * Chat providers in priority order when several are enabled at once. Ollama first keeps the
     * historical "Ollama wins ties" contract (and it stays the fallback when nothing is enabled,
     * since its defaults target a stock local install).
     */
    private static final String[][] CHAT_PROVIDERS = {
            {"openfilz.ai.ollama.chat.enabled", OLLAMA},
            {"openfilz.ai.anthropic.chat.enabled", ANTHROPIC},
            {"openfilz.ai.google.chat.enabled", GOOGLE_GENAI},
            {"openfilz.ai.openai.chat.enabled", OPENAI},
    };

    /**
     * Embedding stays restricted to Ollama/OpenAI: Anthropic has no embeddings API, and the
     * pgvector schema is pinned to the 768-dim output of the models these two are configured with.
     */
    private static final String[][] EMBEDDING_PROVIDERS = {
            {"openfilz.ai.ollama.embedding.enabled", OLLAMA},
            {"openfilz.ai.openai.embedding.enabled", OPENAI},
    };

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean aiActive = environment.getProperty(AI_ACTIVE, Boolean.class, false);
        Map<String, Object> selectors = new LinkedHashMap<>();

        putIfAbsent(environment, selectors, CHAT_SELECTOR, aiActive
                ? chatProvider(environment)
                : NONE);
        putIfAbsent(environment, selectors, EMBEDDING_SELECTOR, aiActive
                ? provider(environment, EMBEDDING_PROVIDERS)
                : NONE);
        for (String unused : UNUSED_SELECTORS) {
            putIfAbsent(environment, selectors, unused, NONE);
        }

        if (!selectors.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, selectors));
        }
    }

    /**
     * The chat provider: an explicit switch first, then the fallback chain's first entry, then
     * Ollama. Switches keep precedence so an existing deployment that sets both is unaffected.
     */
    private String chatProvider(ConfigurableEnvironment environment) {
        for (String[] candidate : CHAT_PROVIDERS) {
            if (environment.getProperty(candidate[0], Boolean.class, false)) {
                return candidate[1];
            }
        }
        String fromChain = firstChainProvider(environment);
        if (fromChain != null) {
            return fromChain;
        }
        // Nothing configured: Ollama, whose defaults target a stock local install, so
        // `openfilz.ai.active=true` alone is a working setup.
        return OLLAMA;
    }

    /**
     * Selector named by the first usable {@code provider:model} entry of the fallback chain, or
     * null when failover is off, unset, or the chain names nothing we recognise.
     * <p>
     * Parsed by hand rather than through the bound {@code AiProperties}: an EnvironmentPostProcessor
     * runs long before any bean exists. The chain binds a comma-separated string to a list, so the
     * raw property is read the same way here.
     */
    private String firstChainProvider(ConfigurableEnvironment environment) {
        if (!environment.getProperty(FALLBACK_ENABLED, Boolean.class, false)) {
            return null;
        }
        String chain = environment.getProperty(FALLBACK_CHAIN);
        if (chain == null || chain.isBlank()) {
            return null;
        }
        for (String entry : chain.split(",")) {
            int separator = entry.indexOf(':');
            if (separator <= 0) continue;
            String selector = switch (entry.substring(0, separator).trim().toLowerCase(java.util.Locale.ROOT)) {
                case "google", "google-genai", "gemini" -> GOOGLE_GENAI;
                case "anthropic", "claude" -> ANTHROPIC;
                case "openai", "openai-compatible", "openai_compatible" -> OPENAI;
                default -> null;
            };
            if (selector != null) return selector;
        }
        return null;
    }

    private String provider(ConfigurableEnvironment environment, String[][] candidates) {
        for (String[] candidate : candidates) {
            if (environment.getProperty(candidate[0], Boolean.class, false)) {
                return candidate[1];
            }
        }
        // Fallback when no switch is set: Ollama, whose defaults target a stock local install,
        // so `openfilz.ai.active=true` alone is a working setup.
        return OLLAMA;
    }

    private void putIfAbsent(ConfigurableEnvironment environment, Map<String, Object> selectors,
                             String key, String value) {
        if (environment.getProperty(key) == null) {
            selectors.put(key, value);
        }
    }
}
