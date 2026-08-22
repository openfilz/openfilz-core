package org.openfilz.dms.service.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.genai.Client;
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
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

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

    private final ChatModel defaultChatModel;
    private final ToolCallingManager toolCallingManager;
    private final UserAiSettingsRepository repository;
    private final AiSettingsCipher cipher;
    private final Environment environment;
    private final boolean userSettingsEnabled;

    private final Cache<String, CachedModel> cache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    public UserChatClientResolver(ChatModel defaultChatModel,
                                  ToolCallingManager toolCallingManager,
                                  UserAiSettingsRepository repository,
                                  AiSettingsCipher cipher,
                                  Environment environment,
                                  @Value("${openfilz.ai.user-settings.enabled:false}") boolean userSettingsEnabled) {
        this.defaultChatModel = defaultChatModel;
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
                    .genAiClient(Client.builder()
                            .apiKey(apiKey)
                            .build())
                    .options(GoogleGenAiChatOptions.builder()
                            .model(model)
                            .build())
                    .toolCallingManager(toolCallingManager)
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

    private ResolvedChat defaultChat() {
        String provider = environment.getProperty("spring.ai.model.chat", "none");
        return new ResolvedChat(defaultChatModel, provider, defaultModelFor(provider), defaultKeyRefFor(provider));
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
