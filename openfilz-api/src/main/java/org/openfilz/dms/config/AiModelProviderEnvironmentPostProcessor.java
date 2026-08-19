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
 *       {@code OPENAI_CHAT_ENABLED}, …), Ollama winning when both are enabled. When a kind has no
 *       switch set it falls back to Ollama, whose defaults point at a stock local install
 *       (localhost:11434, qwen2.5, nomic-embed-text) — so turning the feature on is enough.</li>
 *   <li>An explicitly-set {@code spring.ai.model.*} property always wins — the escape hatch for
 *       providers OpenFilz exposes no switch for.</li>
 * </ul>
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

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean aiActive = environment.getProperty(AI_ACTIVE, Boolean.class, false);
        Map<String, Object> selectors = new LinkedHashMap<>();

        putIfAbsent(environment, selectors, CHAT_SELECTOR, aiActive
                ? provider(environment, "openfilz.ai.ollama.chat.enabled", "openfilz.ai.openai.chat.enabled")
                : NONE);
        putIfAbsent(environment, selectors, EMBEDDING_SELECTOR, aiActive
                ? provider(environment, "openfilz.ai.ollama.embedding.enabled", "openfilz.ai.openai.embedding.enabled")
                : NONE);
        for (String unused : UNUSED_SELECTORS) {
            putIfAbsent(environment, selectors, unused, NONE);
        }

        if (!selectors.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, selectors));
        }
    }

    private String provider(ConfigurableEnvironment environment, String ollamaFlag, String openaiFlag) {
        if (environment.getProperty(openaiFlag, Boolean.class, false)
                && !environment.getProperty(ollamaFlag, Boolean.class, false)) {
            return OPENAI;
        }
        // Ollama wins when both are enabled, and is the fallback when neither is: its defaults
        // target a stock local install, so `openfilz.ai.active=true` alone is a working setup.
        return OLLAMA;
    }

    private void putIfAbsent(ConfigurableEnvironment environment, Map<String, Object> selectors,
                             String key, String value) {
        if (environment.getProperty(key) == null) {
            selectors.put(key, value);
        }
    }
}
