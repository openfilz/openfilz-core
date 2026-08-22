package org.openfilz.dms.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

        Set<AiProvider> providers = new LinkedHashSet<>();
        entries.forEach(entry -> providers.add(entry.provider()));

        for (AiProvider provider : providers) {
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

    /** Readable chain summary for the startup log; models only, never keys. */
    private String describe(List<AiFallbackChain.ChainEntry> entries) {
        return entries.stream()
                .map(entry -> entry.provider() + ":" + entry.model())
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
