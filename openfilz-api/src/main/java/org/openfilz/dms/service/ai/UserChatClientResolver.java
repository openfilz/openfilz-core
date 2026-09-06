package org.openfilz.dms.service.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.entity.UserAiSettings;
import org.openfilz.dms.enums.AiProvider;
import org.openfilz.dms.repository.UserAiSettingsRepository;
import org.openfilz.dms.service.impl.AiSettingsCipher;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicSetup;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves the {@link ChatModel} to use for a given user's chat request.
 * <p>
 * When BYOK ({@code openfilz.ai.user-settings.enabled}, read at runtime — native-image-safe)
 * is off, or the user has no personal settings, the server-default model is returned. When the
 * user configured a provider + API key, a provider {@link ChatModel} is built
 * <em>programmatically</em> (no Spring auto-configuration involved, so the
 * {@code spring.ai.model.*} selectors and the GraalVM build-time conditions are untouched).
 * <p>
 * Built models are cached (Caffeine, keyed by user, invalidated on settings change via a
 * config-hash comparison) — they carry the pooled HTTP clients, which is the expensive part.
 * The {@code ChatClient} on top is assembled per request by {@link ChatClientAssembler}.
 */
@Slf4j
@Component
@Lazy
public class UserChatClientResolver {

    /**
     * A resolved chat model with display metadata for the frontend badge, plus a fingerprint of
     * the API key behind it.
     * <p>
     * The fingerprint (never the key) lets {@link AiFallbackChain} bench a model <em>per key</em>:
     * quota is charged per key, so "gemini-3.6-flash is out of quota" is only ever true of one
     * key at a time, and two BYOK users on the same provider and model must not share a cooldown.
     */
    public record ResolvedChat(ChatModel chatModel, String provider, String model, String keyRef) {

        /** For callers with no key to declare; the model is then benched under its own bucket. */
        public ResolvedChat(ChatModel chatModel, String provider, String model) {
            this(chatModel, provider, model, AiKeyRef.UNKNOWN);
        }
    }

    private record CachedModel(String configHash, ResolvedChat resolved) {}

    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(120);

    /**
     * Retry policy for the programmatically built Google model. Spring AI's default template
     * retries transient failures (429s, I/O errors) 10 times with exponential backoff capped at
     * 3 minutes per wait — behind which a single spent free-tier key stalls a chat request for
     * many minutes before {@link AiFallbackChain} even sees the failure. The template guards the
     * blocking call path (tool-execution rounds); streaming errors already surface immediately.
     * The fallback chain is this application's retry mechanism — the right response to a spent
     * key is the <em>next candidate</em>, not more waiting on the same one — so in-model retries
     * are kept to two quick attempts. Anthropic and OpenAI need no equivalent: their models take
     * no template, and their vendor SDK clients are built below with {@code maxRetries} 1.
     * <p>
     * The Google client itself carries {@link #GOOGLE_NO_SDK_RETRY}: beneath this template the
     * GenAI SDK retries a 429 on its own — five attempts with 1 s, 2 s, 4 s, 8 s backoff plus
     * jitter, 19 s against a local server that answers at once, 30 to 50 s against the real API
     * — before {@link AiFallbackChain} even saw the quota error. The SDK's exception is not a
     * {@link TransientAiException}, so this template never re-ran that cycle; the SDK's own
     * retry was the whole stall (measured on the demo: every document's insight and filing paid
     * it on each exhausted model of the chain).
     */
    static final RetryTemplate SHORT_RETRY_TEMPLATE = new RetryTemplate(RetryPolicy.builder()
            .maxRetries(2)
            .includes(TransientAiException.class, ResourceAccessException.class)
            .delay(Duration.ofMillis(500))
            .multiplier(2.0)
            .maxDelay(Duration.ofSeconds(2))
            .build());

    /**
     * One HTTP attempt per call for the Google GenAI SDK ({@code attempts} is floored at 1 by the
     * SDK, so this is "no retry", not "no request"). Failover is the fallback chain's job.
     */
    static final HttpRetryOptions GOOGLE_NO_SDK_RETRY = HttpRetryOptions.builder().attempts(1).build();

    /**
     * The auto-configured server-default chat model — <b>optional</b>, resolved through a provider.
     * <p>
     * A deployment may legitimately have none: {@code spring.ai.model.chat=none} builds no bean, and
     * that is exactly the configuration of an MCP-only deployment and of a "light" one that runs the
     * automatic AI features (embeddings, {@code prototype}/{@code learned} classification, the
     * neighbour vote, by-kind reorganisation) with no LLM. A required injection would make this bean
     * — and with it {@code AiDocumentInsightService} and {@code DefaultAutoFileService}, which need
     * it only for the model stages they may never reach — impossible to create there.
     * Asking for a model when there is none fails per call, with a message that says so.
     */
    private final ObjectProvider<ChatModel> defaultChatModelProvider;
    private final ToolCallingManager toolCallingManager;
    private final UserAiSettingsRepository repository;
    private final AiSettingsCipher cipher;
    private final Environment environment;
    private final boolean userSettingsEnabled;

    private final Cache<String, CachedModel> cache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    /**
     * The server-default primary rebuilt to match the runtime provider selector (see
     * {@link #runtimeSwitchedDefault}), evaluated once: {@code null} until first asked, then
     * empty when the injected bean already matches or nothing can be rebuilt. Selector and keys
     * come from the environment, so the answer cannot change while the process lives.
     */
    private final AtomicReference<Optional<ResolvedChat>> runtimeSwitchedDefault = new AtomicReference<>();

    public UserChatClientResolver(ObjectProvider<ChatModel> defaultChatModelProvider,
                                  ToolCallingManager toolCallingManager,
                                  UserAiSettingsRepository repository,
                                  AiSettingsCipher cipher,
                                  Environment environment,
                                  @Value("${openfilz.ai.user-settings.enabled:false}") boolean userSettingsEnabled) {
        this.defaultChatModelProvider = defaultChatModelProvider;
        this.toolCallingManager = toolCallingManager;
        this.repository = repository;
        this.cipher = cipher;
        this.environment = environment;
        this.userSettingsEnabled = userSettingsEnabled;
    }

    public boolean isUserSettingsEnabled() {
        return userSettingsEnabled;
    }

    /**
     * Resolve the chat model for this user. Falls back to the server default when BYOK is off,
     * the user is anonymous, or no personal settings exist. A broken personal configuration
     * (e.g. undecryptable key after a master-key rotation) propagates as an error so the user
     * sees it and can fix or reset their settings, rather than silently chatting with a model
     * they didn't choose.
     */
    public Mono<ResolvedChat> resolve(String userEmail) {
        if (!userSettingsEnabled || userEmail == null || userEmail.isBlank()
                || UserInfoService.ANONYMOUS_USER.equals(userEmail)) {
            return Mono.just(defaultChat());
        }
        return repository.findById(userEmail)
                .publishOn(Schedulers.boundedElastic()) // client construction does blocking setup work
                .map(settings -> resolveCached(userEmail, settings))
                .defaultIfEmpty(defaultChat());
    }

    /** Drop the cached model after a settings change (save/delete). */
    public void evict(String userEmail) {
        cache.invalidate(userEmail);
    }

    private ResolvedChat resolveCached(String userEmail, UserAiSettings settings) {
        String hash = configHash(settings);
        CachedModel cached = cache.getIfPresent(userEmail);
        if (cached != null && cached.configHash().equals(hash)) {
            return cached.resolved();
        }
        AiProvider provider = AiProvider.valueOf(settings.getProvider());
        String apiKey = cipher.decrypt(settings.getApiKeyEncrypted());
        ChatModel model = buildChatModel(provider, apiKey, settings.getBaseUrl(), settings.getModel());
        ResolvedChat resolved = new ResolvedChat(model, provider.name(), settings.getModel(), AiKeyRef.of(apiKey));
        cache.put(userEmail, new CachedModel(hash, resolved));
        log.debug("[AI] Built {} chat model ({}) for user {}", provider, settings.getModel(), userEmail);
        return resolved;
    }

    /**
     * Build a provider {@link ChatModel} programmatically — used for per-user models and for
     * the settings "test connection" probe. Runtime construction only: native-image-safe.
     */
    public ChatModel buildChatModel(AiProvider provider, String apiKey, String baseUrl, String model) {
        return switch (provider) {
            // Both the sync and async clients must be supplied: the model builders otherwise
            // self-build the missing one from environment variables — which don't exist for a
            // BYOK key — and fail with "at least one credential source must be specified".
            case ANTHROPIC -> AnthropicChatModel.builder()
                    .anthropicClient(AnthropicSetup.setupSyncClient(
                            null, apiKey, PROVIDER_TIMEOUT, 1, null, null))
                    .anthropicClientAsync(AnthropicSetup.setupAsyncClient(
                            null, apiKey, PROVIDER_TIMEOUT, 1, null, null))
                    .options(AnthropicChatOptions.builder()
                            .model(model)
                            .maxTokens(4096)
                            .build())
                    .toolCallingManager(toolCallingManager)
                    .build();
            case GOOGLE -> GoogleGenAiChatModel.builder()
                    .genAiClient(googleClient(apiKey, baseUrl))
                    .options(GoogleGenAiChatOptions.builder()
                            .model(model)
                            .build())
                    .toolCallingManager(toolCallingManager)
                    .retryTemplate(SHORT_RETRY_TEMPLATE)
                    .build();
            case OPENAI, OPENAI_COMPATIBLE -> OpenAiChatModel.builder()
                    .openAiClient(OpenAiSetup.setupSyncClient(
                            provider == AiProvider.OPENAI_COMPATIBLE ? baseUrl : null,
                            apiKey, null, null, null, null, false, false, model,
                            PROVIDER_TIMEOUT, 1, null, null,
                            ObservationRegistry.NOOP, null, List.of()))
                    .openAiClientAsync(OpenAiSetup.setupAsyncClient(
                            provider == AiProvider.OPENAI_COMPATIBLE ? baseUrl : null,
                            apiKey, null, null, null, null, false, false, model,
                            PROVIDER_TIMEOUT, 1, null, null,
                            ObservationRegistry.NOOP, null, List.of()))
                    .options(OpenAiChatOptions.builder()
                            .model(model)
                            .build())
                    .toolCallingManager(toolCallingManager)
                    .build();
        };
    }

    /**
     * The Google GenAI client behind every programmatically built Gemini model: the SDK's own
     * retry off ({@link #GOOGLE_NO_SDK_RETRY}), the same request timeout as the other providers,
     * and an optional base URL — the Gemini Developer API by default, a stand-in server in tests.
     */
    static Client googleClient(String apiKey, String baseUrl) {
        HttpOptions.Builder http = HttpOptions.builder()
                .retryOptions(GOOGLE_NO_SDK_RETRY)
                .timeout((int) PROVIDER_TIMEOUT.toMillis());
        if (baseUrl != null && !baseUrl.isBlank()) {
            http.baseUrl(baseUrl);
        }
        return Client.builder()
                .apiKey(apiKey)
                .httpOptions(http.build())
                .build();
    }

    /** The auto-configured chat model, or null when the deployment builds none. */
    private ChatModel defaultChatModel() {
        return defaultChatModelProvider.getIfAvailable();
    }

    private ResolvedChat defaultChat() {
        String provider = environment.getProperty("spring.ai.model.chat", "none");
        Optional<ResolvedChat> switched = runtimeSwitchedDefault.get();
        if (switched == null) {
            // A lost race just builds the same model twice; the extra one is garbage-collected.
            switched = Optional.ofNullable(runtimeSwitchedDefault(provider));
            runtimeSwitchedDefault.set(switched);
        }
        return switched.orElseGet(() -> {
            ChatModel bean = defaultChatModel();
            if (bean == null) {
                // Deliberate configuration, not a bug: say which features need a model and which
                // do not, so an operator running a light deployment knows whether to care.
                throw new IllegalStateException("No chat model is configured on this deployment"
                        + " (spring.ai.model.chat=" + provider + ")."
                        + " The chat assistant, the 'llm' insight classifier and smart-filing stage 2"
                        + " need one; classification with the prototype/learned classifier, the"
                        + " neighbour vote, the by-kind reorganisation and the MCP server do not.");
            }
            return new ResolvedChat(bean, provider, defaultModelFor(provider), defaultKeyRefFor(provider));
        });
    }

    /**
     * The server-default primary rebuilt to match the runtime provider selector, or null when
     * the injected bean already matches (every JVM deployment) or nothing can be rebuilt.
     * <p>
     * In the GraalVM native image, Spring AI's provider auto-configurations are bean
     * <em>conditions</em>, evaluated at build time: the AOT run bakes a single {@code ChatModel}
     * bean — Ollama, the selector's default at that point — and setting
     * {@code spring.ai.model.chat} at runtime can no longer swap it. Without this seam the
     * "chain's first entry names the primary" contract silently breaks natively: the primary
     * keeps calling Ollama while wearing the runtime provider's name, pays a failing call on
     * every chat request, and — worse — its failures are benched in {@link AiFallbackChain}'s
     * cooldown registry under the <em>real</em> provider:key:model of a healthy chain candidate.
     * So when the selector names a provider this resolver can build programmatically and the
     * bean is visibly a different provider, the primary is built here, through the same
     * native-safe {@link #buildChatModel} path BYOK and the chain already use.
     * <p>
     * Rebuilt from the provider's single server key ({@code spring.ai.*.api-key}). A deployment
     * that only has pool keys ({@code AI_FALLBACK_KEYS_*}) keeps the baked bean as primary and
     * is carried by the chain, exactly as before. The label deliberately stays the selector's
     * name in every fallback-to-the-bean case: relabelling the primary as the bean's own
     * provider ("ollama") would trip the chain's data-residency rule and turn failover off in
     * precisely the deployment being rescued by it.
     */
    private ResolvedChat runtimeSwitchedDefault(String selector) {
        AiProvider wanted = buildableProvider(selector);
        if (wanted == null) {
            return null;
        }
        ChatModel bean = defaultChatModel();
        if (bean != null) {
            String actual = beanProvider(bean);
            // Unrecognised bean (a test mock) is trusted as-is; a matching one needs no rebuild.
            if (actual == null || actual.equals(selector)) {
                return null;
            }
        }
        // bean == null is the third case: the selector names a provider we can build and the
        // deployment auto-configured none (native image with the selector switched at runtime, or
        // a JVM deployment whose starter is absent). Building it here is the only way to have one.
        String apiKey = AiFallbackChain.serverApiKey(wanted, environment);
        String model = defaultModelFor(selector);
        if (apiKey == null || model.isBlank()) {
            log.warn("[AI] Chat provider selector '{}' does not match the {} compiled into this image, "
                            + "and there is no server API key + model to rebuild it from — keeping the "
                            + "compiled bean; the fallback chain (if configured) still applies",
                    selector, beanName(bean));
            return null;
        }
        try {
            ChatModel built = buildChatModel(wanted, apiKey, null, model);
            log.info("[AI] Runtime chat provider '{}' differs from the {} compiled into this image — "
                            + "built the {} ({}) primary programmatically (native-image runtime switch)",
                    selector, beanName(bean), wanted, model);
            return new ResolvedChat(built, selector, model, AiKeyRef.of(apiKey));
        } catch (Exception e) {
            log.warn("[AI] Could not build the '{}' primary programmatically — keeping the compiled {}: {}",
                    selector, beanName(bean), e.toString());
            return null;
        }
    }

    /** {@link AiProvider} the selector names, or null when it names nothing we can build (ollama, none). */
    private static AiProvider buildableProvider(String selector) {
        return switch (selector) {
            case "google-genai" -> AiProvider.GOOGLE;
            case "anthropic" -> AiProvider.ANTHROPIC;
            case "openai" -> AiProvider.OPENAI;
            default -> null;
        };
    }

    /**
     * Selector name of the provider a {@code ChatModel} bean belongs to, or null for a class we
     * don't map — an unrecognised model (or a test mock) is trusted as-is rather than replaced.
     */
    /** Log label for the auto-configured bean, or "absent" when the deployment builds none. */
    private static String beanName(ChatModel model) {
        return model == null ? "absent chat model" : model.getClass().getSimpleName();
    }

    private static String beanProvider(ChatModel model) {
        if (model instanceof OllamaChatModel) return "ollama";
        if (model instanceof GoogleGenAiChatModel) return "google-genai";
        if (model instanceof AnthropicChatModel) return "anthropic";
        if (model instanceof OpenAiChatModel) return "openai";
        return null;
    }

    /**
     * Fingerprint of the key the active provider's auto-configuration is using, so the server
     * default and a fallback-chain entry naming the same provider+model+key are recognised as the
     * same candidate instead of both being tried.
     */
    private String defaultKeyRefFor(String provider) {
        String property = switch (provider) {
            case "anthropic" -> "spring.ai.anthropic.api-key";
            case "google-genai" -> "spring.ai.google.genai.api-key";
            case "openai" -> "spring.ai.openai.api-key";
            default -> null;   // ollama needs no key
        };
        if (property == null) return AiKeyRef.UNKNOWN;
        String key = environment.getProperty(property);
        // 'disabled' is the application.yml sentinel that keeps auto-configuration from failing.
        return (key == null || key.isBlank() || "disabled".equalsIgnoreCase(key))
                ? AiKeyRef.UNKNOWN : AiKeyRef.of(key);
    }

    /** Display model for the server default, resolved from the active provider's config. */
    private String defaultModelFor(String provider) {
        return switch (provider) {
            case "ollama" -> lookup("spring.ai.ollama.chat.model", "spring.ai.ollama.chat.options.model");
            case "anthropic" -> lookup("spring.ai.anthropic.chat.model", "spring.ai.anthropic.chat.options.model");
            case "google-genai" -> lookup("spring.ai.google.genai.chat.model", "spring.ai.google.genai.chat.options.model");
            case "openai" -> lookup("spring.ai.openai.chat.model", "spring.ai.openai.chat.options.model");
            default -> "";
        };
    }

    private String lookup(String shortcut, String canonical) {
        return environment.getProperty(shortcut, environment.getProperty(canonical, ""));
    }

    private String configHash(UserAiSettings settings) {
        // The ciphertext changes on every re-encryption (fresh IV), so a key update always
        // invalidates; a spurious rebuild after re-saving the same key is harmless.
        return settings.getProvider() + '|' + settings.getModel() + '|'
                + settings.getBaseUrl() + '|' + settings.getApiKeyEncrypted();
    }
}
