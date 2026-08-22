package org.openfilz.dms.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.enums.AiProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asks a provider which models a given API key can use, so the BYOK picker offers what actually
 * exists today instead of a list baked into a release.
 * <p>
 * Talks to the providers over plain HTTP rather than their SDKs: the "list models" endpoints are
 * three trivial GETs, and keeping provider SDK types out of this path avoids the reflection that
 * GraalVM native images object to. It also means an unknown OpenAI-compatible gateway works with
 * no extra code, as long as it implements {@code /v1/models}.
 * <p>
 * Results are cached briefly per (provider, key): the settings page asks on every provider change
 * and every key edit, and a listing is not worth a round trip each time. The cache is keyed by the
 * key's {@link AiKeyRef} fingerprint — a raw API key must never become a map key, a log line, or
 * anything else that outlives the request.
 */
@Slf4j
@Component
@Lazy
public class AiModelDirectory {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private static final String GOOGLE_MODELS_URL =
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000";
    private static final String ANTHROPIC_MODELS_URL = "https://api.anthropic.com/v1/models?limit=1000";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String OPENAI_DEFAULT_BASE_URL = "https://api.openai.com";

    private record CacheEntry(List<String> models, Instant expiresAt) {}

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public AiModelDirectory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build();
    }

    /**
     * The chat models this key can use, best default first.
     * <p>
     * Errors are not swallowed here — the caller decides whether a provider that cannot be reached
     * means an error or the built-in list.
     */
    public Mono<List<String>> listChatModels(AiProvider provider, String apiKey, String baseUrl) {
        String cacheKey = cacheKey(provider, apiKey, baseUrl);
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return Mono.just(cached.models());
        }
        return fetch(provider, apiKey, baseUrl)
                .map(models -> AiModelCatalog.ordered(provider, models))
                .doOnNext(models -> {
                    cache.put(cacheKey, new CacheEntry(models, Instant.now().plus(CACHE_TTL)));
                    log.debug("[AI-MODELS] {} listed {} chat model(s) for key {}",
                            provider, models.size(), AiKeyRef.of(apiKey));
                });
    }

    private Mono<List<String>> fetch(AiProvider provider, String apiKey, String baseUrl) {
        return switch (provider) {
            case GOOGLE -> get(GOOGLE_MODELS_URL, headers -> headers.set("x-goog-api-key", apiKey))
                    .map(this::parseGoogle);
            case ANTHROPIC -> get(ANTHROPIC_MODELS_URL, headers -> {
                headers.set("x-api-key", apiKey);
                headers.set("anthropic-version", ANTHROPIC_VERSION);
            }).map(this::parseOpenAiShaped);
            case OPENAI, OPENAI_COMPATIBLE -> get(modelsUrl(baseUrl),
                    headers -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey))
                    .map(this::parseOpenAiShaped);
        };
    }

    private Mono<String> get(String url, java.util.function.Consumer<HttpHeaders> headers) {
        return webClient.get()
                .uri(url)
                .headers(headers::accept)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(TIMEOUT);
    }

    /**
     * {@code /v1/models} on the given base. A base URL that already carries the version segment is
     * left alone, because both spellings are common in the wild for OpenAI-compatible gateways.
     * <p>
     * Package-private, like the two parsers below: they carry the behaviour worth testing, and a
     * test that calls them directly beats one that reflects into them.
     */
    static String modelsUrl(String baseUrl) {
        String base = (baseUrl == null || baseUrl.isBlank()) ? OPENAI_DEFAULT_BASE_URL : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/v1") ? base + "/models" : base + "/v1/models";
    }

    /**
     * Google: {@code {"models":[{"name":"models/gemini-3.6-flash","supportedGenerationMethods":[...]}]}}.
     * The capability list is the provider's own verdict on whether a model can hold a conversation,
     * so it is trusted ahead of the id heuristic.
     */
    List<String> parseGoogle(String body) {
        List<String> models = new ArrayList<>();
        for (JsonNode model : arrayAt(body, "models")) {
            String name = model.path("name").asString("");
            String id = name.startsWith("models/") ? name.substring("models/".length()) : name;
            Boolean generates = null;
            JsonNode methods = model.path("supportedGenerationMethods");
            if (methods.isArray()) {
                generates = false;
                for (JsonNode method : methods) {
                    if ("generateContent".equals(method.asString(""))) {
                        generates = true;
                        break;
                    }
                }
            }
            if (AiModelCatalog.isChatModel(id, generates)) {
                models.add(id);
            }
        }
        return models;
    }

    /** OpenAI and Anthropic both answer {@code {"data":[{"id":"..."}]}}. */
    List<String> parseOpenAiShaped(String body) {
        List<String> models = new ArrayList<>();
        for (JsonNode model : arrayAt(body, "data")) {
            String id = model.path("id").asString("");
            if (AiModelCatalog.isChatModel(id, null)) {
                models.add(id);
            }
        }
        return models;
    }

    private Iterable<JsonNode> arrayAt(String body, String field) {
        JsonNode node = objectMapper.readTree(body).path(field);
        return node.isArray() ? node : List.of();
    }

    /** Fingerprint, never the key itself — this value is held in a map for the cache's lifetime. */
    private static String cacheKey(AiProvider provider, String apiKey, String baseUrl) {
        return provider.name() + ':' + AiKeyRef.of(apiKey)
                + (provider == AiProvider.OPENAI_COMPATIBLE ? ':' + modelsUrl(baseUrl) : "");
    }
}
