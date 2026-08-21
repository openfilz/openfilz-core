package org.openfilz.dms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.request.SaveAiSettingsRequest;
import org.openfilz.dms.dto.response.AiConnectionTestResult;
import org.openfilz.dms.dto.response.AiSettingsResponse;
import org.openfilz.dms.entity.UserAiSettings;
import org.openfilz.dms.enums.AiProvider;
import org.openfilz.dms.repository.UserAiSettingsRepository;
import org.openfilz.dms.service.AiSettingsService;
import org.openfilz.dms.service.ai.UserChatClientResolver;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Implementation of the per-user BYOK settings. Keys are stored AES-256-GCM encrypted
 * ({@link AiSettingsCipher}); the resolver cache is evicted on every change.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "openfilz.ai.active", havingValue = "true")
public class AiSettingsServiceImpl implements AiSettingsService {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String TEST_PROMPT = "Reply with the single word: OK";

    private final UserAiSettingsRepository repository;
    private final AiSettingsCipher cipher;
    private final UserChatClientResolver resolver;
    private final Environment environment;

    public AiSettingsServiceImpl(UserAiSettingsRepository repository,
                                 AiSettingsCipher cipher,
                                 UserChatClientResolver resolver,
                                 Environment environment) {
        this.repository = repository;
        this.cipher = cipher;
        this.resolver = resolver;
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
        return repository.findById(userEmail)
                .map(existing -> {
                    if (isBlank(request.apiKey()) && existing.getApiKeyEncrypted() == null) {
                        throw badRequest("apiKey is required");
                    }
                    existing.setProvider(request.provider().name());
                    existing.setModel(request.model().trim());
                    existing.setBaseUrl(normalizeBaseUrl(request));
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
                            .baseUrl(normalizeBaseUrl(request))
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
                    ChatModel model = resolver.buildChatModel(
                            request.provider(), apiKey, normalizeBaseUrl(request), request.model().trim());
                    String reply = model.call(TEST_PROMPT);
                    long latency = System.currentTimeMillis() - start;
                    log.debug("AI connection test OK for {} ({} ms): {}", request.provider(), latency, reply);
                    return new AiConnectionTestResult(true, "Connection successful", latency);
                })
                .timeout(TEST_TIMEOUT,
                        Mono.just(new AiConnectionTestResult(false,
                                "Timed out after " + TEST_TIMEOUT.getSeconds() + "s", TEST_TIMEOUT.toMillis())))
                .onErrorResume(e -> {
                    if (e instanceof ResponseStatusException) {
                        return Mono.error(e); // validation errors stay 4xx — only provider failures become ok=false
                    }
                    log.debug("AI connection test failed for {}: {}", request.provider(), e.toString());
                    return Mono.just(new AiConnectionTestResult(false, friendlyMessage(e), 0));
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

    private String normalizeBaseUrl(SaveAiSettingsRequest request) {
        if (request.provider() != AiProvider.OPENAI_COMPATIBLE || isBlank(request.baseUrl())) {
            return null;
        }
        String url = request.baseUrl().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw badRequest("baseUrl must start with http:// or https://");
        }
        return url;
    }

    private AiSettingsResponse toResponse(UserAiSettings settings) {
        String key = cipher.decrypt(settings.getApiKeyEncrypted());
        return baseResponse()
                .provider(settings.getProvider())
                .model(settings.getModel())
                .baseUrl(settings.getBaseUrl())
                .hasApiKey(true)
                .keySuffix(key.length() > 4 ? key.substring(key.length() - 4) : "")
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
