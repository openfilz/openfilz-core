package org.openfilz.dms.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.enums.AiProvider;
import org.openfilz.dms.service.ai.AiFailoverPolicy.Failure;
import org.openfilz.dms.service.ai.UserChatClientResolver.ResolvedChat;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ordered list of chat models to try for one request, plus the per-model cooldown registry
 * that remembers which ones are currently out of quota.
 * <p>
 * Free provider tiers (Google's especially) hand out small per-minute and per-day allowances, so
 * a busy afternoon can exhaust the primary model. With
 * {@code openfilz.ai.fallback.enabled=true} the chat pipeline then walks
 * {@code openfilz.ai.fallback.chain} — {@code provider:model} entries, tried in order — instead of
 * failing the user's question.
 * <p>
 * Two distinct mechanisms, and both matter:
 * <ul>
 *   <li><b>Failover</b> — the request that hits the quota is retried on the next model, so the
 *       user still gets an answer (see {@code AiChatServiceImpl}).</li>
 *   <li><b>Cooldown</b> — the model that failed is marked unavailable for a while, so the
 *       <em>following</em> requests skip straight to a model that works instead of each paying a
 *       failing round-trip first. This is what stops a spent daily quota from adding latency to
 *       every request for the rest of the day. Cooldowns expire on their own, so the primary
 *       model comes back into rotation without an operator touching anything.</li>
 * </ul>
 * Cooldown state is per-instance and in-memory: it is a latency optimisation, not a correctness
 * mechanism, so a restart (or a second replica warming up its own view) simply costs one failed
 * call per model before it re-learns.
 * <p>
 * Chain entries are limited to the API-key providers OpenFilz already builds programmatically
 * ({@code GOOGLE}, {@code ANTHROPIC}, {@code OPENAI}, {@code OPENAI_COMPATIBLE}) — the same
 * {@link UserChatClientResolver#buildChatModel} path BYOK uses. Ollama is deliberately absent:
 * it is the server default's own business, has no quota to exhaust, and its model is built by
 * Spring AI auto-configuration rather than by hand.
 */
@Slf4j
@Component
@Lazy
public class AiFallbackChain {

    private final AiProperties aiProperties;
    private final UserChatClientResolver resolver;
    private final Environment environment;

    /** Model key -> instant the cooldown expires. Absent (or past) means the model is usable. */
    private final Map<String, Instant> cooldowns = new ConcurrentHashMap<>();

    /** Built fallback models, cached because each one carries a pooled HTTP client. */
    private final Map<String, ResolvedChat> models = new ConcurrentHashMap<>();

    /** Provider keys whose API key is missing, so we stop rebuilding (and re-logging) them. */
    private final Set<String> unconfigured = ConcurrentHashMap.newKeySet();

    public AiFallbackChain(AiProperties aiProperties, UserChatClientResolver resolver, Environment environment) {
        this.aiProperties = aiProperties;
        this.resolver = resolver;
        this.environment = environment;
    }

    /**
     * The models to try for this request, best first.
     * <p>
     * The user's own (or the server default) model leads unless it is cooling down, in which case
     * the first healthy fallback takes its place. If every candidate is cooling down the primary
     * is returned anyway: a real error from a real attempt beats refusing to try.
     */
    public List<ResolvedChat> candidates(ResolvedChat primary) {
        AiProperties.Fallback config = aiProperties.getFallback();
        if (!config.isEnabled() || config.getChain().isEmpty()) {
            return List.of(primary);
        }
        return candidates(primary, Instant.now());
    }

    /** Package-private seam so tests can drive cooldown expiry without sleeping. */
    List<ResolvedChat> candidates(ResolvedChat primary, Instant now) {
        List<ResolvedChat> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        String primaryKey = key(primary.provider(), primary.model());
        seen.add(primaryKey);
        if (isHealthy(primaryKey, now)) {
            out.add(primary);
        } else {
            log.debug("[AI-FALLBACK] Skipping primary {} — cooling down until {}", primaryKey, cooldowns.get(primaryKey));
        }

        for (String entry : aiProperties.getFallback().getChain()) {
            ResolvedChat candidate = parseAndBuild(entry, seen, now);
            if (candidate != null) {
                out.add(candidate);
            }
        }

        if (out.isEmpty()) {
            log.warn("[AI-FALLBACK] Every candidate is cooling down — retrying the primary {} anyway", primaryKey);
            return List.of(primary);
        }
        return out;
    }

    /** Parse one {@code provider:model} chain entry into a usable candidate, or null to skip it. */
    private ResolvedChat parseAndBuild(String entry, Set<String> seen, Instant now) {
        if (entry == null || entry.isBlank()) return null;

        int separator = entry.indexOf(':');
        if (separator <= 0 || separator == entry.length() - 1) {
            log.warn("[AI-FALLBACK] Ignoring malformed chain entry '{}' — expected 'provider:model'", entry);
            return null;
        }
        AiProvider provider = provider(entry.substring(0, separator).trim());
        String model = entry.substring(separator + 1).trim();
        if (provider == null) {
            log.warn("[AI-FALLBACK] Ignoring chain entry '{}' — unknown provider (expected one of "
                    + "google, anthropic, openai, openai-compatible)", entry);
            return null;
        }

        String modelKey = key(provider.name(), model);
        if (!seen.add(modelKey)) return null;          // already the primary, or listed twice
        if (!isHealthy(modelKey, now)) {
            log.debug("[AI-FALLBACK] Skipping {} — cooling down until {}", modelKey, cooldowns.get(modelKey));
            return null;
        }
        if (unconfigured.contains(provider.name())) return null;

        ResolvedChat cached = models.get(modelKey);
        if (cached != null) return cached;

        String apiKey = apiKey(provider);
        if (apiKey == null) {
            // Remembered, so a chain listing a provider the deployment has no key for costs one
            // warning rather than one per request.
            unconfigured.add(provider.name());
            log.warn("[AI-FALLBACK] Chain entry '{}' is unusable — no API key configured for {}", entry, provider);
            return null;
        }
        try {
            ChatModel chatModel = resolver.buildChatModel(provider, apiKey, baseUrl(provider), model);
            ResolvedChat built = new ResolvedChat(chatModel, provider.name(), model);
            models.put(modelKey, built);
            log.info("[AI-FALLBACK] Prepared fallback model {}", modelKey);
            return built;
        } catch (Exception e) {
            // A provider client that refuses to build must not take the whole chat request down:
            // the remaining candidates are still worth trying.
            log.warn("[AI-FALLBACK] Could not build fallback model {} — skipping it: {}", modelKey, e.toString());
            unconfigured.add(provider.name());
            return null;
        }
    }

    /**
     * Record that a model just failed, so subsequent requests skip it until its cooldown expires.
     * A retired model gets the longer cooldown — unlike a spent quota it will not come back.
     */
    public void trip(ResolvedChat chat, Failure failure) {
        trip(chat, failure, Instant.now());
    }

    /** Package-private seam so tests can drive cooldown expiry without sleeping. */
    void trip(ResolvedChat chat, Failure failure, Instant now) {
        if (!failure.shouldFailover()) return;
        AiProperties.Fallback config = aiProperties.getFallback();
        Duration cooldown = failure == Failure.MODEL_UNAVAILABLE
                ? config.getUnavailableCooldown()
                : config.getQuotaCooldown();
        if (cooldown == null || cooldown.isZero() || cooldown.isNegative()) return;

        String modelKey = key(chat.provider(), chat.model());
        cooldowns.put(modelKey, now.plus(cooldown));
        log.warn("[AI-FALLBACK] {} on {} — benching it for {}", failure, modelKey, cooldown);
    }

    /** Whether this model may be tried right now. */
    boolean isHealthy(String modelKey, Instant now) {
        Instant until = cooldowns.get(modelKey);
        if (until == null) return true;
        if (now.isBefore(until)) return false;
        cooldowns.remove(modelKey, until);   // cooldown served — back into rotation
        return true;
    }

    /**
     * Canonical cooldown key. Needed because the same model reaches us under two different
     * provider spellings: Spring AI's selector name for the server default ({@code google-genai})
     * and the {@link AiProvider} name for BYOK and chain entries ({@code GOOGLE}).
     */
    static String key(String provider, String model) {
        return canonicalProvider(provider) + ':' + (model == null ? "" : model.trim().toLowerCase(Locale.ROOT));
    }

    private static String canonicalProvider(String provider) {
        if (provider == null) return "";
        String normalised = provider.trim().toLowerCase(Locale.ROOT);
        return switch (normalised) {
            case "google-genai", "google", "gemini", "googlegenai" -> "google";
            case "anthropic", "claude" -> "anthropic";
            case "openai" -> "openai";
            case "openai_compatible", "openai-compatible" -> "openai-compatible";
            case "ollama" -> "ollama";
            default -> normalised;
        };
    }

    /** Map a chain entry's provider token onto the enum, or null when it names nothing we can build. */
    private static AiProvider provider(String token) {
        return switch (canonicalProvider(token)) {
            case "google" -> AiProvider.GOOGLE;
            case "anthropic" -> AiProvider.ANTHROPIC;
            case "openai" -> AiProvider.OPENAI;
            case "openai-compatible" -> AiProvider.OPENAI_COMPATIBLE;
            default -> null;
        };
    }

    /**
     * The server-configured API key for a provider, or null when there is none.
     * {@code disabled} is the sentinel application.yml uses to keep provider auto-configuration
     * from failing at startup, so it counts as "not configured" here too.
     */
    private String apiKey(AiProvider provider) {
        String property = switch (provider) {
            case GOOGLE -> "spring.ai.google.genai.api-key";
            case ANTHROPIC -> "spring.ai.anthropic.api-key";
            case OPENAI, OPENAI_COMPATIBLE -> "spring.ai.openai.api-key";
        };
        String value = environment.getProperty(property);
        return (value == null || value.isBlank() || "disabled".equalsIgnoreCase(value)) ? null : value;
    }

    private String baseUrl(AiProvider provider) {
        return provider == AiProvider.OPENAI_COMPATIBLE
                ? environment.getProperty("spring.ai.openai.base-url")
                : null;
    }
}
