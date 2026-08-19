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
 * Translates the OpenFilz per-provider AI switches into the Spring AI model selectors.
 * <p>
 * Spring AI 2.0 removed the per-provider {@code spring.ai.<provider>.chat.enabled} flags. A
 * provider's auto-configuration is now gated on a single selector — {@code spring.ai.model.chat}
 * and {@code spring.ai.model.embedding} — whose value is the provider name, or {@code none} to
 * disable it. Those conditions are {@code matchIfMissing = true}, so leaving the selector unset
 * with both the Ollama and OpenAI starters on the classpath would instantiate <em>both</em>
 * providers' models.
 * <p>
 * OpenFilz keeps exposing the boolean switches ({@code OLLAMA_CHAT_ENABLED},
 * {@code OPENAI_CHAT_ENABLED}, …) that deployments already use, and this post-processor derives
 * the selectors from them. Ollama wins when both are enabled; when neither is, the selector is set
 * to {@code none} so no provider model is built at all.
 * <p>
 * An explicitly-set {@code spring.ai.model.*} property always takes precedence — it is the escape
 * hatch for providers OpenFilz does not expose a boolean for.
 * <p>
 * The ordering matters: the switches this reads come from {@code application.yml}, so it has to run
 * after {@code ConfigDataEnvironmentPostProcessor} has contributed the config data — otherwise every
 * switch would read as absent and every provider would silently resolve to {@code none}.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
public class AiModelProviderEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "openfilzAiModelProviders";

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
        Map<String, Object> selectors = new LinkedHashMap<>();

        putIfAbsent(environment, selectors, CHAT_SELECTOR,
                provider(environment, "openfilz.ai.ollama.chat.enabled", "openfilz.ai.openai.chat.enabled"));
        putIfAbsent(environment, selectors, EMBEDDING_SELECTOR,
                provider(environment, "openfilz.ai.ollama.embedding.enabled", "openfilz.ai.openai.embedding.enabled"));
        for (String unused : UNUSED_SELECTORS) {
            putIfAbsent(environment, selectors, unused, NONE);
        }

        if (!selectors.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, selectors));
        }
    }

    private String provider(ConfigurableEnvironment environment, String ollamaFlag, String openaiFlag) {
        if (environment.getProperty(ollamaFlag, Boolean.class, false)) {
            return OLLAMA;
        }
        if (environment.getProperty(openaiFlag, Boolean.class, false)) {
            return OPENAI;
        }
        return NONE;
    }

    private void putIfAbsent(ConfigurableEnvironment environment, Map<String, Object> selectors,
                             String key, String value) {
        if (environment.getProperty(key) == null) {
            selectors.put(key, value);
        }
    }
}
