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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ordered list of (model, API key) candidates to try for one request, plus the cooldown
 * registry that remembers which combinations are currently out of quota.
 * <p>
 * Free provider tiers meter requests <em>per key</em>, per model, per minute and per day, so a
 * busy afternoon exhausts the primary model. With {@code openfilz.ai.fallback.enabled=true} the
 * chat pipeline walks {@code openfilz.ai.fallback.chain} — {@code provider:model} entries, tried
 * in order — and each provider may carry a pool of keys in
 * {@code openfilz.ai.fallback.keys.<provider>} instead of the single {@code spring.ai.*.api-key}.
 *
 * <h2>How candidates are ordered</h2>
 * A provider is exhausted completely — every chain model on every key — before the next provider
 * is touched:
 * <pre>
 *   google:     m1/keyA   m2/keyA   m1/keyB   m2/keyB
 *   anthropic:  c1/keyX   c1/keyY
 * </pre>
 * So the key rotates as soon as a provider has nothing left to offer on the current one, and a
 * different provider is only reached once the previous one is spent outright. Chain order decides
 * <em>provider</em> priority (by first appearance) and model priority within a provider; an
 * interleaved chain such as {@code google:m1,anthropic:c1,google:m2} is therefore grouped as
 * {@code google:m1,google:m2} then {@code anthropic:c1}, because key rotation is inherently a
 * per-provider decision.
 * <p>
 * Which key is "current" is derived from the cooldown registry rather than held in a pointer: a
 * provider's usable keys are those with at least one chain model still healthy. A key that went
 * out of quota an hour ago simply drops out of the list, and rejoins it when the cooldown lapses.
 *
 * <h2>Two mechanisms, both needed</h2>
 * <ul>
 *   <li><b>Failover</b> — the request that hit the quota is retried on the next candidate, so the
 *       user still gets an answer (see {@code AiChatServiceImpl}).</li>
 *   <li><b>Cooldown</b> — the failed (model, key) pair is benched, so the <em>following</em>
 *       requests skip it instead of each paying a failing round-trip. Without it a spent daily
 *       quota would add a failing call to every request for the rest of the day. Cooldowns expire
 *       on their own, returning the pair to rotation with no operator action.</li>
 * </ul>
 * Cooldown state is per-instance and in-memory: a latency optimisation, not a correctness
 * mechanism, so a restart (or a second replica) costs one failed call per pair before it relearns.
 * <p>
 * Chain entries are limited to the API-key providers OpenFilz already builds programmatically —
 * the same {@link UserChatClientResolver#buildChatModel} path BYOK uses. Ollama is deliberately
 * absent: it is local, has no quota to exhaust, and its model comes from Spring AI
 * auto-configuration rather than being built by hand.
 */
@Slf4j
@Component
@Lazy
public class AiFallbackChain {

    /** One {@code provider:model} entry of the configured chain. */
    public record ChainEntry(AiProvider provider, String model) {}

    /** Provider selector value Spring AI uses for a local Ollama install. */
    static final String OLLAMA = "ollama";

    private final AiProperties aiProperties;
    private final UserChatClientResolver resolver;
    private final Environment environment;

    /** {@code provider:keyRef:model} -> instant the cooldown expires. Absent or past means usable. */
    private final Map<String, Instant> cooldowns = new ConcurrentHashMap<>();

    /** Built models, cached because each carries a pooled HTTP client. Keyed like the cooldowns. */
    private final Map<String, ResolvedChat> models = new ConcurrentHashMap<>();

    /** {@code provider:keyRef} pairs whose client refused to build, so we stop retrying them. */
    private final Set<String> unusable = ConcurrentHashMap.newKeySet();

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
        if (!config.isEnabled() || config.getChain().isEmpty() || isLocal(primary)) {
            return List.of(primary);
        }
        return candidates(primary, Instant.now());
    }

    /**
     * Whether the model answering this request is a local Ollama one, in which case the chain is
     * deliberately ignored.
     * <p>
     * This is a data-residency rule, not a performance one. An operator who runs a local LLM does
     * so precisely because document content must not leave the deployment, and failing over would
     * send the RAG context — actual document text — to a third-party API on a transient blip. A
     * local model going down is an outage to fix, not something to silently route around.
     * <p>
     * The test is on the model actually in use rather than on the server-wide selector, so a BYOK
     * user who deliberately picked a cloud provider still gets failover on a deployment whose
     * default is Ollama: their content is already leaving the building by their own choice.
     */
    private static boolean isLocal(ResolvedChat chat) {
        return OLLAMA.equals(canonicalProvider(chat.provider()));
    }

    /** Package-private seam so tests can drive cooldown expiry without sleeping. */
    List<ResolvedChat> candidates(ResolvedChat primary, Instant now) {
        List<ResolvedChat> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        String primaryKey = cooldownKey(primary.provider(), primary.keyRef(), primary.model());
        seen.add(primaryKey);
        if (isHealthy(primaryKey, now)) {
            out.add(primary);
        } else {
            log.debug("[AI-FALLBACK] Skipping primary {} — cooling down until {}", primaryKey, cooldowns.get(primaryKey));
        }

        // Group the chain by provider, keeping each provider's first appearance as its priority
        // and the chain order of its own models. Key rotation is a per-provider decision, so a
        // provider has to be handled as a unit rather than entry by entry.
        Map<AiProvider, List<String>> chainModels = modelsByProvider(parseChain());
        Map<AiProvider, List<String>> usableKeys = usableKeysByProvider(chainModels, now);

        chainModels.forEach((provider, providerModels) -> {
            for (String apiKey : usableKeys.getOrDefault(provider, List.of())) {
                for (String model : providerModels) {
                    ResolvedChat candidate = candidate(provider, model, apiKey, seen, now);
                    if (candidate != null) {
                        out.add(candidate);
                    }
                }
            }
        });

        if (out.isEmpty()) {
            log.warn("[AI-FALLBACK] Every candidate is cooling down — retrying the primary {} anyway", primaryKey);
            return List.of(primary);
        }
        return out;
    }

    /** Build (or reuse) the candidate for one model on one key, or null when it is unusable. */
    private ResolvedChat candidate(AiProvider provider, String model, String apiKey, Set<String> seen, Instant now) {
        String keyRef = AiKeyRef.of(apiKey);
        String modelKey = cooldownKey(provider.name(), keyRef, model);

        if (!seen.add(modelKey)) return null;          // already the primary, or listed twice
        if (!isHealthy(modelKey, now)) return null;    // benched (usableKeys only checked the provider)
        if (unusable.contains(providerKey(provider, keyRef))) return null;

        ResolvedChat cached = models.get(modelKey);
        if (cached != null) return cached;

        try {
            ChatModel chatModel = resolver.buildChatModel(provider, apiKey, baseUrl(provider), model);
            ResolvedChat built = new ResolvedChat(chatModel, provider.name(), model, keyRef);
            models.put(modelKey, built);
            log.info("[AI-FALLBACK] Prepared fallback model {}", modelKey);
            return built;
        } catch (Exception e) {
            // A provider client that refuses to build must not take the whole chat request down:
            // the remaining candidates are still worth trying.
            log.warn("[AI-FALLBACK] Could not build fallback model {} — skipping it: {}", modelKey, e.toString());
            unusable.add(providerKey(provider, keyRef));
            return null;
        }
    }

    /** Chain models grouped by provider, both kept in chain order (providers by first appearance). */
    private Map<AiProvider, List<String>> modelsByProvider(List<ChainEntry> entries) {
        Map<AiProvider, List<String>> grouped = new LinkedHashMap<>();
        for (ChainEntry entry : entries) {
            List<String> models = grouped.computeIfAbsent(entry.provider(), p -> new ArrayList<>());
            if (!models.contains(entry.model())) {
                models.add(entry.model());
            }
        }
        return grouped;
    }

    /**
     * Per provider, the keys still worth trying: those with at least one chain model not benched.
     * <p>
     * Filtering here (rather than per model) is what makes key rotation provider-wide — a key
     * disappears from the list only once the provider has nothing left to offer on it.
     */
    private Map<AiProvider, List<String>> usableKeysByProvider(Map<AiProvider, List<String>> chainModels, Instant now) {
        Map<AiProvider, List<String>> usable = new LinkedHashMap<>();
        chainModels.forEach((provider, providerModels) -> {
            List<String> keys = new ArrayList<>();
            for (String apiKey : keyPool(provider)) {
                String keyRef = AiKeyRef.of(apiKey);
                if (unusable.contains(providerKey(provider, keyRef))) continue;
                boolean anyModelHealthy = providerModels.stream()
                        .anyMatch(model -> isHealthy(cooldownKey(provider.name(), keyRef, model), now));
                if (anyModelHealthy) {
                    keys.add(apiKey);
                } else {
                    log.debug("[AI-FALLBACK] {} key {} is spent for every chain model — rotating past it",
                            provider, keyRef);
                }
            }
            usable.put(provider, keys);
        });
        return usable;
    }

    /**
     * The keys to try for a provider: its configured pool, or the single server API key when no
     * pool is set (so an existing single-key deployment keeps working untouched).
     */
    private List<String> keyPool(AiProvider provider) {
        return keyPool(aiProperties.getFallback(), provider, environment);
    }

    /** Bean-free form, shared with the startup validator (see {@link #parseChain}). */
    public static List<String> keyPool(AiProperties.Fallback fallback, AiProvider provider, Environment environment) {
        List<String> configured = fallback.getKeys().get(provider);
        List<String> pool = configured == null ? List.of() : configured.stream()
                .filter(key -> key != null && !key.isBlank() && !"disabled".equalsIgnoreCase(key.trim()))
                .map(String::trim)
                .distinct()
                .toList();
        if (!pool.isEmpty()) return pool;

        String single = serverApiKey(provider, environment);
        return single == null ? List.of() : List.of(single);
    }

    /**
     * Take a whole API key out of rotation because the provider refused it.
     * <p>
     * Unlike a spent quota this has no cooldown: a key the provider rejects is a configuration
     * error, and it will keep being rejected until an operator changes it — at which point the
     * process restarts anyway and the registry is empty again. Every model already built on that
     * key is dropped with it, since they all carry the same refused credential.
     * <p>
     * Reserved for keys that came from a <em>pool</em>: refusing the active model's own key is
     * reported to the caller instead, so a single-key deployment cannot be silently rerouted.
     *
     * @return true when this call is what disabled the key (so the caller logs it once)
     */
    public boolean disableKey(ResolvedChat chat) {
        String providerKey = providerKey(chat);
        if (providerKey == null) {
            return false;   // nothing identifiable to disable — do not bench an unrelated key
        }
        if (!unusable.add(providerKey)) {
            return false;   // already disabled by this or a concurrent request
        }
        models.keySet().removeIf(modelKey -> modelKey.startsWith(providerKey + ':'));
        return true;
    }

    /**
     * Whether this candidate's key is still in rotation. The candidate list of a request is built
     * once, up front, so a key disabled part-way through it is still listed for that request —
     * the caller checks this before each attempt to avoid paying one refused call per model.
     */
    public boolean isUsable(ResolvedChat chat) {
        String providerKey = providerKey(chat);
        return providerKey == null || !unusable.contains(providerKey);
    }

    /** {@code provider:keyRef} for a resolved model, or null when its key has no fingerprint. */
    private static String providerKey(ResolvedChat chat) {
        String keyRef = chat.keyRef();
        if (keyRef == null || keyRef.isBlank() || AiKeyRef.UNKNOWN.equals(keyRef)) return null;
        return canonicalProvider(chat.provider()) + ':' + keyRef;
    }

    /**
     * Record that a candidate just failed, so subsequent requests skip that (model, key) pair
     * until its cooldown expires. A retired model gets the longer cooldown — unlike a spent quota
     * it will not come back.
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

        String modelKey = cooldownKey(chat.provider(), chat.keyRef(), chat.model());
        cooldowns.put(modelKey, now.plus(cooldown));
        log.warn("[AI-FALLBACK] {} on {} — benching it for {}", failure, modelKey, cooldown);
    }

    /** Whether this (provider, key, model) may be tried right now. */
    boolean isHealthy(String modelKey, Instant now) {
        Instant until = cooldowns.get(modelKey);
        if (until == null) return true;
        if (now.isBefore(until)) return false;
        cooldowns.remove(modelKey, until);   // cooldown served — back into rotation
        return true;
    }

    /** Parse the configured chain, dropping (and reporting) entries we cannot act on. */
    private List<ChainEntry> parseChain() {
        return parseChain(aiProperties.getFallback().getChain(), rejected -> {});
    }

    /**
     * Parse {@code provider:model} entries, handing every unusable one to {@code onRejected}.
     * <p>
     * Static and bean-free on purpose: {@code AiFallbackValidator} runs at startup and must not
     * pull in this component, whose {@code ChatModel} dependency does not exist when the AI
     * feature is switched off.
     */
    public static List<ChainEntry> parseChain(List<String> chain, java.util.function.Consumer<String> onRejected) {
        List<ChainEntry> entries = new ArrayList<>();
        if (chain == null) return entries;
        for (String entry : chain) {
            if (entry == null || entry.isBlank()) continue;
            int separator = entry.indexOf(':');
            if (separator <= 0 || separator == entry.length() - 1) {
                onRejected.accept(entry + " (expected 'provider:model')");
                continue;
            }
            AiProvider provider = provider(entry.substring(0, separator).trim());
            if (provider == null) {
                onRejected.accept(entry + " (unknown provider — expected google, anthropic, "
                        + "openai or openai-compatible)");
                continue;
            }
            entries.add(new ChainEntry(provider, entry.substring(separator + 1).trim()));
        }
        return entries;
    }

    /**
     * Canonical cooldown key. Needed because the same model reaches us under two different
     * provider spellings: Spring AI's selector name for the server default ({@code google-genai})
     * and the {@link AiProvider} name for BYOK and chain entries ({@code GOOGLE}).
     */
    static String cooldownKey(String provider, String keyRef, String model) {
        return canonicalProvider(provider)
                + ':' + (keyRef == null || keyRef.isBlank() ? AiKeyRef.UNKNOWN : keyRef)
                + ':' + (model == null ? "" : model.trim().toLowerCase(Locale.ROOT));
    }

    private static String providerKey(AiProvider provider, String keyRef) {
        return canonicalProvider(provider.name()) + ':' + keyRef;
    }

    static String canonicalProvider(String provider) {
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
    static AiProvider provider(String token) {
        return switch (canonicalProvider(token)) {
            case "google" -> AiProvider.GOOGLE;
            case "anthropic" -> AiProvider.ANTHROPIC;
            case "openai" -> AiProvider.OPENAI;
            case "openai-compatible" -> AiProvider.OPENAI_COMPATIBLE;
            default -> null;
        };
    }

    /**
     * The single server-configured API key for a provider, or null when there is none.
     * {@code disabled} is the sentinel application.yml uses to keep provider auto-configuration
     * from failing at startup, so it counts as "not configured" here too.
     */
    static String serverApiKey(AiProvider provider, Environment environment) {
        String property = switch (provider) {
            case GOOGLE -> "spring.ai.google.genai.api-key";
            case ANTHROPIC -> "spring.ai.anthropic.api-key";
            case OPENAI, OPENAI_COMPATIBLE -> "spring.ai.openai.api-key";
        };
        String value = environment.getProperty(property);
        return (value == null || value.isBlank() || "disabled".equalsIgnoreCase(value)) ? null : value;
    }

    /**
     * A deployment-configured {@code provider:model} (e.g. {@code openfilz.ai.insights.model}),
     * built with the provider's server key (the fallback key pool, else
     * {@code spring.ai.<provider>.api-key}) and cached. Empty when unset, unparseable, without a
     * key, or when the client refuses to build; callers then use the chat model.
     */
    public java.util.Optional<ResolvedChat> configuredModel(String providerModel) {
        if (providerModel == null || providerModel.isBlank()) {
            return java.util.Optional.empty();
        }
        List<ChainEntry> entries = parseChain(List.of(providerModel.trim()),
                rejected -> log.warn("[AI] configured model ignored: {}", rejected));
        if (entries.isEmpty()) {
            return java.util.Optional.empty();
        }
        ChainEntry entry = entries.getFirst();
        List<String> keys = keyPool(entry.provider());
        if (keys.isEmpty()) {
            log.warn("[AI] configured model {} has no API key for {}", providerModel, entry.provider());
            return java.util.Optional.empty();
        }
        String apiKey = keys.getFirst();
        String keyRef = AiKeyRef.of(apiKey);
        String modelKey = cooldownKey(entry.provider().name(), keyRef, entry.model());
        ResolvedChat cached = models.get(modelKey);
        if (cached != null) {
            return java.util.Optional.of(cached);
        }
        try {
            ChatModel chatModel = resolver.buildChatModel(entry.provider(), apiKey, baseUrl(entry.provider()), entry.model());
            ResolvedChat built = new ResolvedChat(chatModel, entry.provider().name(), entry.model(), keyRef);
            models.put(modelKey, built);
            return java.util.Optional.of(built);
        } catch (Exception e) {
            log.warn("[AI] configured model {} could not be built: {}", providerModel, e.toString());
            return java.util.Optional.empty();
        }
    }

    private String baseUrl(AiProvider provider) {
        return provider == AiProvider.OPENAI_COMPATIBLE
                ? environment.getProperty("spring.ai.openai.base-url")
                : null;
    }
}
