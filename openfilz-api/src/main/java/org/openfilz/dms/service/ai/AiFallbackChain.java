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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
 * the same {@link UserChatClientResolver#buildChatModel} path BYOK uses — plus the managed
 * {@value #OPENFILZ_CLOUD} provider: the OpenFilz AI gateway, an OpenAI-compatible endpoint
 * addressed with {@code openfilz.ai.cloud.url} and the tenant key {@code openfilz.ai.cloud.api-key}
 * (no key pool). It is a provider of its own for cooldowns and key rotation even though it is
 * built as {@link AiProvider#OPENAI_COMPATIBLE}: it never shares a key or a base URL with a
 * deployment's own OpenAI-compatible endpoint. Ollama is deliberately absent: it is local, has no
 * quota to exhaust, and its model comes from Spring AI auto-configuration rather than being built
 * by hand.
 */
@Slf4j
@Component
@Lazy
public class AiFallbackChain {

    /**
     * One {@code provider:model} entry of the configured chain.
     *
     * @param provider the client type to build ({@link AiProvider#OPENAI_COMPATIBLE} for the managed provider)
     * @param model    the model name, passed through as written (the gateway resolves {@code default} itself)
     * @param name     the canonical provider token the entry was written with ({@code google},
     *                 {@code openfilz-cloud}…): what groups entries per provider and keys the cooldowns
     */
    public record ChainEntry(AiProvider provider, String model, String name) {

        public ChainEntry {
            name = name == null || name.isBlank() ? canonicalProvider(provider.name()) : canonicalProvider(name);
        }

        /** A vendor entry, named after its {@link AiProvider}. */
        public ChainEntry(AiProvider provider, String model) {
            this(provider, model, null);
        }

        /** Whether this entry goes to the OpenFilz AI gateway rather than to a vendor the deployment holds a key for. */
        public boolean managed() {
            return OPENFILZ_CLOUD.equals(name);
        }

        /** The provider label stored on the {@link ResolvedChat}: the token for the managed provider, the enum name otherwise. */
        String providerLabel() {
            return managed() ? OPENFILZ_CLOUD : provider.name();
        }
    }

    /** Provider selector value Spring AI uses for a local Ollama install. */
    static final String OLLAMA = "ollama";

    /** Provider token of the managed OpenFilz AI gateway ({@code openfilz.ai.cloud.*}). */
    public static final String OPENFILZ_CLOUD = "openfilz-cloud";

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
        return candidatesFor(primary, Instant.now());
    }

    /** {@link #candidates(ResolvedChat)} at a given instant — the seam the tests drive time through. */
    List<ResolvedChat> candidatesFor(ResolvedChat primary, Instant now) {
        AiProperties.Fallback config = aiProperties.getFallback();
        if (!config.isEnabled() || config.getChain().isEmpty() || isLocal(primary)) {
            return List.of(primary);
        }
        return candidates(primary, now);
    }

    /**
     * Run one blocking model call with the same failover the chat stream gets
     * ({@code AiChatServiceImpl#streamWithFailover}): walk the candidates best first, classify each
     * failure ({@link AiFailoverPolicy}), bench the model that failed so later calls skip it, and
     * retry on the next usable candidate when the verdict allows it. A refused key surfaces when it
     * is the primary's own (an operator has to fix it) and is dropped from the pool when it came
     * from one. The last failure propagates unchanged when nothing is left to try, so the caller
     * can still classify and describe it.
     * <p>
     * Built for the background callers — document insights, smart filing — whose single call per
     * document used to die on the first 429 of a busy minute.
     *
     * @param tag  log prefix, e.g. {@code INSIGHTS}
     * @param call the call to make; it receives the candidate to use
     */
    public <T> T callWithFailover(ResolvedChat primary, String tag, Function<ResolvedChat, T> call) {
        return callWithFailover(primary, tag, call, Instant.now());
    }

    /** Package-private seam so tests can drive cooldown expiry without sleeping. */
    <T> T callWithFailover(ResolvedChat primary, String tag, Function<ResolvedChat, T> call, Instant now) {
        List<ResolvedChat> candidates = candidatesFor(primary, now);
        RuntimeException last = null;
        for (int index = 0; index < candidates.size(); index++) {
            ResolvedChat candidate = candidates.get(index);
            if (!isUsable(candidate)) {
                continue;   // its key was disabled earlier in this same walk
            }
            try {
                return call.apply(candidate);
            } catch (RuntimeException e) {
                last = e;
                Failure failure = AiFailoverPolicy.classify(e);
                trip(candidate, failure, now);
                // A pool key the provider refuses is retryable on the next key, though not on
                // another model of the same key — so it gets its own path rather than
                // Failure.shouldFailover().
                boolean pooledKeyRefused = failure == Failure.CREDENTIALS_REJECTED && candidate != primary;
                if (pooledKeyRefused && disableKey(candidate)) {
                    log.error("[{}] {} rejected the fallback API key {} — dropping it from the pool for the rest "
                                    + "of this process; fix or remove it in AI_FALLBACK_KEYS_{}",
                            tag, candidate.provider(), candidate.keyRef(), candidate.provider().toUpperCase(Locale.ROOT));
                }
                if (!failure.shouldFailover() && !pooledKeyRefused) {
                    throw e;
                }
                ResolvedChat next = nextUsable(candidates, index);
                if (next == null) {
                    log.error("[{}] {} on {} ({}, key {}) and no fallback model left: {}", tag, failure,
                            candidate.provider(), candidate.model(), candidate.keyRef(), AiFailoverPolicy.rootMessage(e));
                    throw e;
                }
                log.warn("[{}] {} on {} ({}, key {}) — falling back to {} ({}, key {})", tag, failure,
                        candidate.provider(), candidate.model(), candidate.keyRef(),
                        next.provider(), next.model(), next.keyRef());
            }
        }
        throw last != null ? last : new IllegalStateException("no usable model in the fallback chain");
    }

    /** The next candidate after {@code index} whose key is still in rotation, or null. */
    private ResolvedChat nextUsable(List<ResolvedChat> candidates, int index) {
        for (int i = index + 1; i < candidates.size(); i++) {
            if (isUsable(candidates.get(i))) {
                return candidates.get(i);
            }
        }
        return null;
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
        Map<String, List<ChainEntry>> chainModels = entriesByProvider(parseChain());
        Map<String, List<String>> usableKeys = usableKeysByProvider(chainModels, now);

        chainModels.forEach((provider, entries) -> {
            for (String apiKey : usableKeys.getOrDefault(provider, List.of())) {
                for (ChainEntry entry : entries) {
                    ResolvedChat candidate = candidate(entry, apiKey, seen, now);
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
    private ResolvedChat candidate(ChainEntry entry, String apiKey, Set<String> seen, Instant now) {
        String keyRef = AiKeyRef.of(apiKey);
        String modelKey = cooldownKey(entry.name(), keyRef, entry.model());

        if (!seen.add(modelKey)) return null;          // already the primary, or listed twice
        if (!isHealthy(modelKey, now)) return null;    // benched (usableKeys only checked the provider)
        if (unusable.contains(providerKey(entry.name(), keyRef))) return null;

        ResolvedChat cached = models.get(modelKey);
        if (cached != null) return cached;

        try {
            ResolvedChat built = build(entry, apiKey, keyRef);
            models.put(modelKey, built);
            log.info("[AI-FALLBACK] Prepared fallback model {}", modelKey);
            return built;
        } catch (Exception e) {
            // A provider client that refuses to build must not take the whole chat request down:
            // the remaining candidates are still worth trying.
            log.warn("[AI-FALLBACK] Could not build fallback model {} — skipping it: {}", modelKey, e.toString());
            unusable.add(providerKey(entry.name(), keyRef));
            return null;
        }
    }

    /** The client for one entry on one key: the vendor SDK, or the gateway for the managed provider. */
    private ResolvedChat build(ChainEntry entry, String apiKey, String keyRef) {
        ChatModel chatModel = resolver.buildChatModel(entry.provider(), apiKey, baseUrl(entry), entry.model());
        return new ResolvedChat(chatModel, entry.providerLabel(), entry.model(), keyRef);
    }

    /** Chain entries grouped by provider token, both kept in chain order (providers by first appearance), duplicates dropped. */
    private Map<String, List<ChainEntry>> entriesByProvider(List<ChainEntry> entries) {
        Map<String, List<ChainEntry>> grouped = new LinkedHashMap<>();
        for (ChainEntry entry : entries) {
            List<ChainEntry> models = grouped.computeIfAbsent(entry.name(), p -> new ArrayList<>());
            if (models.stream().noneMatch(m -> m.model().equals(entry.model()))) {
                models.add(entry);
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
    private Map<String, List<String>> usableKeysByProvider(Map<String, List<ChainEntry>> chainModels, Instant now) {
        Map<String, List<String>> usable = new LinkedHashMap<>();
        chainModels.forEach((provider, entries) -> {
            List<String> keys = new ArrayList<>();
            for (String apiKey : keyPool(entries.getFirst())) {
                String keyRef = AiKeyRef.of(apiKey);
                if (unusable.contains(providerKey(provider, keyRef))) continue;
                boolean anyModelHealthy = entries.stream()
                        .anyMatch(entry -> isHealthy(cooldownKey(provider, keyRef, entry.model()), now));
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
     * The keys to try for an entry's provider: the tenant key alone for the managed provider,
     * else its configured pool, or the single server API key when no pool is set (so an existing
     * single-key deployment keeps working untouched).
     */
    private List<String> keyPool(ChainEntry entry) {
        return keyPool(aiProperties, entry, environment);
    }

    /** Bean-free form of {@link #keyPool(ChainEntry)}, shared with the startup validator. */
    public static List<String> keyPool(AiProperties aiProperties, ChainEntry entry, Environment environment) {
        if (entry.managed()) {
            AiProperties.Cloud cloud = aiProperties.getCloud();
            return cloud.isConfigured() ? List.of(cloud.getApiKey().trim()) : List.of();
        }
        return keyPool(aiProperties.getFallback(), entry.provider(), environment);
    }

    /** Bean-free form for a vendor provider, shared with the startup validator and BYOK (see {@link #parseChain}). */
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
            String token = canonicalProvider(entry.substring(0, separator));
            AiProvider provider = provider(token);
            if (provider == null) {
                onRejected.accept(entry + " (unknown provider — expected google, anthropic, "
                        + "openai, openai-compatible or " + OPENFILZ_CLOUD + ")");
                continue;
            }
            entries.add(new ChainEntry(provider, entry.substring(separator + 1).trim(), token));
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

    private static String providerKey(String provider, String keyRef) {
        return canonicalProvider(provider) + ':' + keyRef;
    }

    static String canonicalProvider(String provider) {
        if (provider == null) return "";
        String normalised = provider.trim().toLowerCase(Locale.ROOT);
        return switch (normalised) {
            case "google-genai", "google", "gemini", "googlegenai" -> "google";
            case "anthropic", "claude" -> "anthropic";
            case "openai" -> "openai";
            case "openai_compatible", "openai-compatible" -> "openai-compatible";
            case "openfilz_cloud", "openfilz-cloud" -> OPENFILZ_CLOUD;
            case "ollama" -> "ollama";
            default -> normalised;
        };
    }

    /**
     * Map a chain entry's provider token onto the client type to build, or null when it names
     * nothing we can build. The managed provider is an OpenAI-compatible client pointed at the
     * gateway; what makes it its own provider (key, base URL, cooldowns) is the entry's name.
     */
    static AiProvider provider(String token) {
        return switch (canonicalProvider(token)) {
            case "google" -> AiProvider.GOOGLE;
            case "anthropic" -> AiProvider.ANTHROPIC;
            case "openai" -> AiProvider.OPENAI;
            case "openai-compatible", OPENFILZ_CLOUD -> AiProvider.OPENAI_COMPATIBLE;
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
     * {@code spring.ai.<provider>.api-key}; the tenant key for {@value #OPENFILZ_CLOUD}) and
     * cached. Empty when unset, unparseable, without a key, or when the client refuses to build;
     * callers then use the chat model.
     */
    public Optional<ResolvedChat> configuredModel(String providerModel) {
        if (providerModel == null || providerModel.isBlank()) {
            return Optional.empty();
        }
        List<ChainEntry> entries = parseChain(List.of(providerModel.trim()),
                rejected -> log.warn("[AI] configured model ignored: {}", rejected));
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        ChainEntry entry = entries.getFirst();
        List<String> keys = keyPool(entry);
        if (keys.isEmpty()) {
            log.warn("[AI] configured model {} has no API key for {}{}", providerModel, entry.name(),
                    entry.managed() ? " — set OPENFILZ_AI_CLOUD_API_KEY" : "");
            return Optional.empty();
        }
        String apiKey = keys.getFirst();
        String keyRef = AiKeyRef.of(apiKey);
        String modelKey = cooldownKey(entry.name(), keyRef, entry.model());
        ResolvedChat cached = models.get(modelKey);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            ResolvedChat built = build(entry, apiKey, keyRef);
            models.put(modelKey, built);
            return Optional.of(built);
        } catch (Exception e) {
            log.warn("[AI] configured model {} could not be built: {}", providerModel, e.toString());
            return Optional.empty();
        }
    }

    /** The endpoint of an OpenAI-compatible entry: the gateway for the managed provider, the deployment's own otherwise. */
    private String baseUrl(ChainEntry entry) {
        if (entry.managed()) {
            return aiProperties.getCloud().getUrl();
        }
        return entry.provider() == AiProvider.OPENAI_COMPATIBLE
                ? environment.getProperty("spring.ai.openai.base-url")
                : null;
    }
}
