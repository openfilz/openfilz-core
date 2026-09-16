package org.openfilz.dms.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiModelProviderEnvironmentPostProcessor;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.enums.AiProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks the chat-model fallback configuration once, at startup.
 * <p>
 * Without this, a chain entry naming a provider the deployment has no API key for stays invisible
 * until the day the primary model runs out of quota — precisely the moment the fallback was
 * supposed to save. Validating at boot turns a silent loss of resilience into a startup error.
 * <p>
 * Deliberately depends on nothing but configuration: {@link AiFallbackChain} needs a
 * {@code ChatModel} bean, which does not exist when the AI feature is switched off, so this uses
 * that class's static helpers instead of injecting it. Being an {@link ApplicationRunner} it runs
 * eagerly, and eagerly instantiating the chain would defeat the feature's runtime toggle.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiFallbackValidator implements ApplicationRunner {

    private final AiProperties aiProperties;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        checkModel();
        AiProperties.Fallback fallback = aiProperties.getFallback();
        if (!aiProperties.isActive() || !fallback.isEnabled()) {
            return;
        }

        if (fallback.getChain().isEmpty()) {
            log.warn("[AI-FALLBACK] Enabled but no chain is configured — there is nothing to fail over to. "
                    + "Set openfilz.ai.fallback.chain (AI_FALLBACK_CHAIN) or turn the feature off.");
            return;
        }

        // A local LLM was chosen so document content stays in the deployment; failing over to a
        // cloud provider would break exactly that guarantee, so the chain is ignored outright.
        // Contradictory rather than broken configuration, so it is reported, not fatal.
        String chatProvider = AiFallbackChain.canonicalProvider(environment.getProperty("spring.ai.model.chat", ""));
        if (AiFallbackChain.OLLAMA.equals(chatProvider)) {
            log.warn("[AI-FALLBACK] The chat provider is Ollama, so the fallback chain is IGNORED: a local "
                    + "model is deployed to keep document content in-house, and failing over would send "
                    + "RAG context to a third-party API. Unset AI_FALLBACK_ENABLED, or choose a cloud chat "
                    + "provider, to silence this.");
            return;
        }

        List<String> problems = new ArrayList<>();
        List<AiFallbackChain.ChainEntry> entries =
                AiFallbackChain.parseChain(fallback.getChain(), rejected -> problems.add("unusable entry '" + rejected + "'"));

        // One check per provider token: the managed provider is checked against its own tenant
        // key and gateway URL, never against the OpenAI key or base URL it merely borrows the
        // client type of.
        Set<String> providers = new LinkedHashSet<>();
        for (AiFallbackChain.ChainEntry entry : entries) {
            if (!providers.add(entry.name())) {
                continue;
            }
            if (entry.managed()) {
                if (!aiProperties.getCloud().isConfigured()) {
                    problems.add("no API key for %s — set OPENFILZ_AI_CLOUD_API_KEY".formatted(AiFallbackChain.OPENFILZ_CLOUD));
                }
                if (isBlank(aiProperties.getCloud().getUrl())) {
                    problems.add("%s needs a gateway URL — set OPENFILZ_AI_CLOUD_URL".formatted(AiFallbackChain.OPENFILZ_CLOUD));
                }
                continue;
            }
            AiProvider provider = entry.provider();
            if (AiFallbackChain.keyPool(fallback, provider, environment).isEmpty()) {
                problems.add("no API key for %s — set %s (or %s)"
                        .formatted(provider, poolVariable(provider), singleKeyVariable(provider)));
            }
            if (provider == AiProvider.OPENAI_COMPATIBLE
                    && isBlank(environment.getProperty("spring.ai.openai.base-url"))) {
                problems.add("openai-compatible needs a base URL — set OPENAI_BASE_URL");
            }
        }

        if (entries.isEmpty()) {
            problems.add("the chain has no usable entries at all");
        }

        if (problems.isEmpty()) {
            log.info("[AI-FALLBACK] Chain validated: {} model(s) across {} provider(s) — {}",
                    entries.size(), providers.size(), describe(entries));
            return;
        }

        String detail = "Chat-model fallback is misconfigured:\n  - " + String.join("\n  - ", problems);
        if (fallback.getValidation() == AiProperties.Fallback.Validation.FAIL_FAST) {
            throw new IllegalStateException(detail
                    + "\nFix the configuration, or set openfilz.ai.fallback.validation=WARN "
                    + "(AI_FALLBACK_VALIDATION) to start anyway with a shorter chain.");
        }
        log.error("[AI-FALLBACK] {}\nStarting anyway because validation=WARN — the chain is shorter than "
                + "configured, so a quota failure may go unanswered.", detail);
    }

    /**
     * {@code openfilz.ai.model} is translated by {@link AiModelProviderEnvironmentPostProcessor},
     * which runs before logging exists and silently ignores an unusable value — reported here so a
     * typo does not leave the deployment quietly on the default model. Never fatal.
     */
    void checkModel() {
        String model = aiProperties.getModel();
        if (model == null || model.isBlank()) {
            return;
        }
        if (!aiProperties.isActive()) {
            log.warn("[AI] OPENFILZ_AI_MODEL is set ('{}') but the AI feature is off — set OPENFILZ_AI_ACTIVE=true "
                    + "to use it", model.trim());
            return;
        }
        String reason = AiModelProviderEnvironmentPostProcessor.invalidModelReason(model);
        if (reason != null) {
            log.warn("[AI] OPENFILZ_AI_MODEL='{}' is IGNORED: {}", model.trim(), reason);
            return;
        }
        String provider = AiFallbackChain.canonicalProvider(model.substring(0, model.indexOf(':')));
        if (AiFallbackChain.OPENFILZ_CLOUD.equals(provider)
                && !aiProperties.getInsights().isActive() && !aiProperties.getAutoFile().isActive()) {
            log.warn("[AI] OPENFILZ_AI_MODEL names {}, which serves document insights and smart filing only "
                    + "(never the chat), but neither is active — set OPENFILZ_AI_INSIGHTS_ACTIVE / "
                    + "OPENFILZ_AI_AUTO_FILE_ACTIVE", AiFallbackChain.OPENFILZ_CLOUD);
        }
    }

    /** Readable chain summary for the startup log; models only, never keys. */
    private String describe(List<AiFallbackChain.ChainEntry> entries) {
        return entries.stream()
                .map(entry -> entry.name() + ":" + entry.model())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private static String poolVariable(AiProvider provider) {
        return "AI_FALLBACK_KEYS_" + provider.name();
    }

    private static String singleKeyVariable(AiProvider provider) {
        return switch (provider) {
            case GOOGLE -> "GOOGLE_API_KEY";
            case ANTHROPIC -> "ANTHROPIC_API_KEY";
            case OPENAI, OPENAI_COMPATIBLE -> "OPENAI_API_KEY";
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
