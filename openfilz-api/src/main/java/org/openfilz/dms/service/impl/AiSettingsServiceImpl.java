package org.openfilz.dms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.request.ListAiModelsRequest;
import org.openfilz.dms.dto.request.SaveAiSettingsRequest;
import org.openfilz.dms.dto.response.AiConnectionTestResult;
import org.openfilz.dms.dto.response.AiModelsResponse;
import org.openfilz.dms.dto.response.AiSettingsResponse;
import org.openfilz.dms.entity.UserAiSettings;
import org.openfilz.dms.exception.AiKeyUnreadableException;
import org.openfilz.dms.enums.AiProvider;
import org.openfilz.dms.repository.UserAiSettingsRepository;
import org.openfilz.dms.service.AiSettingsService;
import org.openfilz.dms.service.ai.AiBaseUrlPolicy;
import org.openfilz.dms.service.ai.AiModelCatalog;
import org.openfilz.dms.service.ai.AiModelDirectory;
import org.openfilz.dms.service.ai.UserChatClientResolver;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Implementation of the per-user BYOK settings. Keys are stored AES-256-GCM encrypted
 * ({@link AiSettingsCipher}); the resolver cache is evicted on every change.
 */
@Slf4j
@Service
@Lazy
public class AiSettingsServiceImpl implements AiSettingsService {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String TEST_PROMPT = "Reply with the single word: OK";

    private final UserAiSettingsRepository repository;
    private final AiSettingsCipher cipher;
    private final UserChatClientResolver resolver;
    private final AiModelDirectory modelDirectory;
    private final Environment environment;
    private final AiBaseUrlPolicy baseUrlPolicy;

    public AiSettingsServiceImpl(UserAiSettingsRepository repository,
                                 AiSettingsCipher cipher,
                                 UserChatClientResolver resolver,
                                 AiModelDirectory modelDirectory,
                                 Environment environment,
                                 AiBaseUrlPolicy baseUrlPolicy) {
        this.baseUrlPolicy = baseUrlPolicy;
        this.repository = repository;
        this.cipher = cipher;
        this.resolver = resolver;
        this.modelDirectory = modelDirectory;
        this.environment = environment;
    }

    @Override
    public Mono<AiSettingsResponse> getSettings(String userEmail) {
        return repository.findById(userEmail)
                .map(this::toResponse)
                .defaultIfEmpty(emptyResponse());
    }

    @Override
    public Mono<AiSettingsResponse> saveSettings(String userEmail, SaveAiSettingsRequest request) {
        requireEnabled();
        validate(request, false);
        return checkedBaseUrl(request.provider(), request.baseUrl())
                .flatMap(baseUrl -> save(userEmail, request, baseUrl.orElse(null)));
    }

    private Mono<AiSettingsResponse> save(String userEmail, SaveAiSettingsRequest request, String baseUrl) {
        return repository.findById(userEmail)
                .map(existing -> {
                    if (isBlank(request.apiKey()) && existing.getApiKeyEncrypted() == null) {
                        throw badRequest("apiKey is required");
                    }
                    if (isBlank(request.apiKey())) {
                        cipher.decrypt(existing.getApiKeyEncrypted()); // keeping a key no key opens: 409 AI_KEY_UNREADABLE
                    }
                    existing.setProvider(request.provider().name());
                    existing.setModel(request.model().trim());
                    existing.setBaseUrl(baseUrl);
                    if (!isBlank(request.apiKey())) {
                        existing.setApiKeyEncrypted(cipher.encrypt(request.apiKey().trim()));
                    }
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setNew(false);
                    return existing;
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    if (isBlank(request.apiKey())) {
                        throw badRequest("apiKey is required");
                    }
                    return UserAiSettings.builder()
                            .userEmail(userEmail)
                            .provider(request.provider().name())
                            .model(request.model().trim())
                            .baseUrl(baseUrl)
                            .apiKeyEncrypted(cipher.encrypt(request.apiKey().trim()))
                            .updatedAt(OffsetDateTime.now())
                            .isNew(true)
                            .build();
                }))
                .flatMap(repository::save)
                .doOnNext(saved -> {
                    resolver.evict(userEmail);
                    log.info("AI settings saved for user {} (provider={}, model={})",
                            userEmail, saved.getProvider(), saved.getModel());
                })
                .map(this::toResponse);
    }

    @Override
    public Mono<Void> deleteSettings(String userEmail) {
        return repository.deleteById(userEmail)
                .doOnSuccess(v -> {
                    resolver.evict(userEmail);
                    log.info("AI settings reset to server default for user {}", userEmail);
                });
    }

    @Override
    public Mono<AiConnectionTestResult> testConnection(String userEmail, SaveAiSettingsRequest request) {
        requireEnabled();
        validate(request, true);
        Mono<String> apiKeyMono = !isBlank(request.apiKey())
                ? Mono.just(request.apiKey().trim())
                : repository.findById(userEmail)
                        .map(settings -> cipher.decrypt(settings.getApiKeyEncrypted()))
                        .switchIfEmpty(Mono.error(badRequest("apiKey is required (no stored key to fall back to)")));

        return apiKeyMono
                .publishOn(Schedulers.boundedElastic())
                .map(apiKey -> {
                    long start = System.currentTimeMillis();
                    // On boundedElastic: the address rule may resolve the host
                    ChatModel model = resolver.buildChatModel(request.provider(), apiKey,
                            normalizeBaseUrl(request.provider(), request.baseUrl()), request.model().trim());
                    String reply = model.call(TEST_PROMPT);
                    long latency = System.currentTimeMillis() - start;
                    log.debug("AI connection test OK for {} ({} ms): {}", request.provider(), latency, reply);
                    return new AiConnectionTestResult(true, "Connection successful", latency);
                })
                .timeout(TEST_TIMEOUT,
                        Mono.just(new AiConnectionTestResult(false,
                                "Timed out after " + TEST_TIMEOUT.getSeconds() + "s", TEST_TIMEOUT.toMillis())))
                .onErrorResume(e -> {
                    if (e instanceof ResponseStatusException || e instanceof AiKeyUnreadableException) {
                        return Mono.error(e); // validation errors stay 4xx — only provider failures become ok=false
                    }
                    log.debug("AI connection test failed for {}: {}", request.provider(), e.toString());
                    return Mono.just(new AiConnectionTestResult(false, friendlyMessage(e), 0));
                });
    }

    @Override
    public Mono<AiModelsResponse> listModels(String userEmail, ListAiModelsRequest request) {
        requireEnabled();
        if (request.provider() == null) {
            throw badRequest("provider is required");
        }
        AiProvider provider = request.provider();
        List<String> builtIn = AiModelCatalog.fallback(provider);

        // The address goes through the same rule as a save, before any request leaves; only an
        // OpenAI-compatible server has one of its own (the others are asked at their fixed address).
        return storedOrSubmittedKey(userEmail, request.apiKey())
                .flatMap(apiKey -> checkedBaseUrl(provider, request.baseUrl())
                        .flatMap(baseUrl -> modelDirectory.listChatModels(provider, apiKey, baseUrl.orElse(null))))
                .map(models -> models.isEmpty()
                        // A provider that answers with nothing usable is not an outage, but it is
                        // not an answer either — offer the built-in list rather than an empty picker.
                        ? AiModelsResponse.fallback(provider, builtIn, "Provider returned no chat models")
                        : AiModelsResponse.live(provider, models))
                // No key yet: the settings page asks as soon as a provider is picked, before the
                // user has typed anything, and that is a normal state rather than an error.
                .switchIfEmpty(Mono.just(AiModelsResponse.fallback(provider, builtIn,
                        "Enter an API key to list the models your account can use")))
                .onErrorResume(e -> {
                    log.debug("[AI-MODELS] {} could not list models: {}", provider, e.toString());
                    return Mono.just(AiModelsResponse.fallback(provider, builtIn, friendlyMessage(e)));
                });
    }

    /**
     * The submitted key, else the stored one, else empty — never an error: see {@link #listModels}. A stored
     * key no configured key opens counts as none (the page shows the built-in list and asks for a key).
     */
    private Mono<String> storedOrSubmittedKey(String userEmail, String submitted) {
        if (!isBlank(submitted)) {
            return Mono.just(submitted.trim());
        }
        return repository.findById(userEmail)
                .flatMap(settings -> {
                    try {
                        return Mono.just(cipher.decrypt(settings.getApiKeyEncrypted()));
                    } catch (AiKeyUnreadableException e) {
                        return Mono.empty();
                    }
                });
    }

    private void requireEnabled() {
        if (!resolver.isUserSettingsEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Per-user AI settings are disabled");
        }
    }

    private void validate(SaveAiSettingsRequest request, boolean forTest) {
        if (request.provider() == null) {
            throw badRequest("provider is required");
        }
        if (isBlank(request.model())) {
            throw badRequest("model is required");
        }
        if (request.provider() == AiProvider.OPENAI_COMPATIBLE && isBlank(request.baseUrl())) {
            throw badRequest("baseUrl is required for OPENAI_COMPATIBLE");
        }
    }

    /**
     * The base URL to use, or null for a provider that has none of its own. Goes through
     * {@link AiBaseUrlPolicy}, which may resolve the host: never call it on an event loop.
     */
    private String normalizeBaseUrl(AiProvider provider, String baseUrl) {
        if (provider != AiProvider.OPENAI_COMPATIBLE || isBlank(baseUrl)) {
            return null;
        }
        return baseUrlPolicy.validate(baseUrl);
    }

    /** {@link #normalizeBaseUrl} off the event loop; empty for a provider without a base URL. */
    private Mono<Optional<String>> checkedBaseUrl(AiProvider provider, String baseUrl) {
        return Mono.fromCallable(() -> Optional.ofNullable(normalizeBaseUrl(provider, baseUrl)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** A stored key no configured key opens still shows the settings, with {@code keyUnreadable} and no suffix. */
    private AiSettingsResponse toResponse(UserAiSettings settings) {
        String key;
        try {
            key = cipher.decrypt(settings.getApiKeyEncrypted());
        } catch (AiKeyUnreadableException e) {
            log.warn("AI settings of user {}: {}", settings.getUserEmail(), e.getMessage());
            key = null;
        }
        return baseResponse()
                .provider(settings.getProvider())
                .model(settings.getModel())
                .baseUrl(settings.getBaseUrl())
                .hasApiKey(true)
                .keySuffix(key == null ? "" : key.length() > 4 ? key.substring(key.length() - 4) : "")
                .keyUnreadable(key == null)
                .build();
    }

    private AiSettingsResponse emptyResponse() {
        return baseResponse().build();
    }

    private AiSettingsResponse.AiSettingsResponseBuilder baseResponse() {
        String defaultProvider = environment.getProperty("spring.ai.model.chat", "none");
        return AiSettingsResponse.builder()
                .enabled(resolver.isUserSettingsEnabled())
                .defaultProvider(defaultProvider)
                .defaultModel(defaultModelFor(defaultProvider));
    }

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

    private static String friendlyMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root instanceof ResponseStatusException refused && refused.getReason() != null) {
            return refused.getReason(); // a refused base URL: the reason alone, not "400 BAD_REQUEST ..."
        }
        String message = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
