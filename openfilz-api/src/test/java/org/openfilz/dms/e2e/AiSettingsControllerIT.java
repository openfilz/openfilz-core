package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.SaveAiSettingsRequest;
import org.openfilz.dms.dto.response.AiConnectionTestResult;
import org.openfilz.dms.dto.response.AiSettingsResponse;
import org.openfilz.dms.dto.response.Settings;
import org.openfilz.dms.entity.UserAiSettings;
import org.openfilz.dms.enums.AiProvider;
import org.openfilz.dms.repository.UserAiSettingsRepository;
import org.openfilz.dms.service.ai.UserChatClientResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.SecureRandom;
import java.util.Base64;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Integration tests for the per-user AI settings API (BYOK): CRUD via REST, key
 * write-only semantics, validation, graceful test-connection failures, and the
 * resolver picking up stored settings. Uses mock Spring AI beans (no real LLM).
 * Runs in no-auth mode, so the connected principal is "anonymousUser".
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
@Import(AiTestConfig.class)
public class AiSettingsControllerIT extends TestContainersBaseConfig {

    private static final String AI_SETTINGS_PREFIX = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS + "/ai";

    /** The no-auth principal the controller resolves for every request in these tests. */
    private static final String CONNECTED_USER = "anonymousUser";

    @Autowired
    protected DatabaseClient databaseClient;

    @Autowired
    protected UserAiSettingsRepository settingsRepository;

    @Autowired
    protected UserChatClientResolver resolver;

    public AiSettingsControllerIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void configureAiProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        registry.add("openfilz.ai.user-settings.enabled", () -> true);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        registry.add("openfilz.ai.user-settings.encryption-key", () -> Base64.getEncoder().encodeToString(key));
        registry.add("spring.ai.openai.api-key", () -> "test-dummy-key");
        // Pin every selector to "none" so AiTestConfig's mocks are the only model beans (see AiChatControllerIT).
        registry.add("spring.ai.model.chat", () -> "none");
        registry.add("spring.ai.model.embedding", () -> "none");
        registry.add("spring.ai.model.image", () -> "none");
        registry.add("spring.ai.model.moderation", () -> "none");
        registry.add("spring.ai.model.audio.speech", () -> "none");
        registry.add("spring.ai.model.audio.transcription", () -> "none");
        registry.add("spring.ai.vectorstore.pgvector.initialize-schema", () -> false);
        registry.add("spring.autoconfigure.exclude", () -> "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration");
    }

    @BeforeEach
    void cleanSettings() {
        databaseClient.sql("DELETE FROM user_ai_settings").then().block();
        resolver.evict(CONNECTED_USER);
    }

    private SaveAiSettingsRequest compatibleRequest(String apiKey) {
        // OPENAI_COMPATIBLE against an unreachable local port: lets tests exercise the real
        // client-construction path without ever calling an external provider.
        return new SaveAiSettingsRequest(AiProvider.OPENAI_COMPATIBLE, "test-model",
                "http://localhost:59999/v1", apiKey);
    }

    private AiSettingsResponse getSettings() {
        return getWebTestClient().get().uri(AI_SETTINGS_PREFIX)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AiSettingsResponse.class)
                .returnResult().getResponseBody();
    }

    private AiSettingsResponse putSettings(SaveAiSettingsRequest request) {
        return getWebTestClient().put().uri(AI_SETTINGS_PREFIX)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AiSettingsResponse.class)
                .returnResult().getResponseBody();
    }

    // ========================= GET =========================

    @Test
    void whenNoSettingsStored_thenGetReturnsDefaults() {
        AiSettingsResponse response = getSettings();

        Assertions.assertNotNull(response);
        Assertions.assertTrue(response.enabled(), "BYOK flag should be on in this test context");
        Assertions.assertNull(response.provider());
        Assertions.assertFalse(response.hasApiKey());
    }

    @Test
    void whenAiUserSettingsEnabled_thenExposedOnGlobalSettings() {
        Settings settings = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Settings.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(settings);
        Assertions.assertTrue(settings.aiActive());
        Assertions.assertTrue(settings.aiUserSettingsEnabled());
    }

    // ========================= PUT =========================

    @Test
    void whenSaveSettings_thenPersistedWithMaskedKey() {
        AiSettingsResponse saved = putSettings(compatibleRequest("sk-test-123456"));

        Assertions.assertNotNull(saved);
        Assertions.assertEquals("OPENAI_COMPATIBLE", saved.provider());
        Assertions.assertEquals("test-model", saved.model());
        Assertions.assertEquals("http://localhost:59999/v1", saved.baseUrl());
        Assertions.assertTrue(saved.hasApiKey());
        Assertions.assertEquals("3456", saved.keySuffix());

        // GET returns the same masked view; the key itself never leaves the server
        AiSettingsResponse fetched = getSettings();
        Assertions.assertEquals("OPENAI_COMPATIBLE", fetched.provider());
        Assertions.assertTrue(fetched.hasApiKey());
        Assertions.assertEquals("3456", fetched.keySuffix());
    }

    @Test
    void whenSaveSettings_thenKeyIsEncryptedAtRest() {
        putSettings(compatibleRequest("sk-test-123456"));

        UserAiSettings stored = settingsRepository.findById(CONNECTED_USER).block();
        Assertions.assertNotNull(stored);
        Assertions.assertNotNull(stored.getApiKeyEncrypted());
        Assertions.assertFalse(stored.getApiKeyEncrypted().contains("sk-test-123456"),
                "API key must not be stored in clear text");
    }

    @Test
    void whenUpdateWithoutApiKey_thenStoredKeyIsKept() {
        putSettings(compatibleRequest("sk-test-123456"));

        AiSettingsResponse updated = putSettings(new SaveAiSettingsRequest(
                AiProvider.OPENAI_COMPATIBLE, "another-model", "http://localhost:59999/v1", null));

        Assertions.assertEquals("another-model", updated.model());
        Assertions.assertTrue(updated.hasApiKey(), "stored key should survive a key-less update");
        Assertions.assertEquals("3456", updated.keySuffix());
    }

    @Test
    void whenFirstSaveWithoutApiKey_thenBadRequest() {
        getWebTestClient().put().uri(AI_SETTINGS_PREFIX)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(compatibleRequest(null)))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void whenCompatibleProviderWithoutBaseUrl_thenBadRequest() {
        getWebTestClient().put().uri(AI_SETTINGS_PREFIX)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(new SaveAiSettingsRequest(
                        AiProvider.OPENAI_COMPATIBLE, "test-model", null, "sk-test-123456")))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void whenSaveWithoutModel_thenBadRequest() {
        getWebTestClient().put().uri(AI_SETTINGS_PREFIX)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(new SaveAiSettingsRequest(
                        AiProvider.ANTHROPIC, "  ", null, "sk-ant-key")))
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ========================= DELETE =========================

    @Test
    void whenDeleteSettings_thenBackToServerDefault() {
        putSettings(compatibleRequest("sk-test-123456"));

        getWebTestClient().delete().uri(AI_SETTINGS_PREFIX)
                .exchange()
                .expectStatus().isOk();

        AiSettingsResponse response = getSettings();
        Assertions.assertNull(response.provider());
        Assertions.assertFalse(response.hasApiKey());
    }

    // ========================= Test connection =========================

    @Test
    void whenTestConnectionAgainstUnreachableEndpoint_thenGracefulFailure() {
        AiConnectionTestResult result = getWebTestClient().post().uri(AI_SETTINGS_PREFIX + "/test")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(compatibleRequest("sk-test-123456")))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AiConnectionTestResult.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(result);
        Assertions.assertFalse(result.ok(), "unreachable endpoint must be reported as a failed test, not an error");
        Assertions.assertNotNull(result.message());
    }

    @Test
    void whenTestConnectionWithoutAnyKey_thenBadRequest() {
        getWebTestClient().post().uri(AI_SETTINGS_PREFIX + "/test")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(compatibleRequest(null)))
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ========================= Resolver =========================

    @Test
    void whenSettingsStored_thenResolverBuildsUserModel() {
        // The controller path stores under the connected (anonymous) principal, which the
        // resolver deliberately skips — so exercise resolution for a named user directly.
        String user = "byok-user@openfilz.org";
        settingsRepository.save(UserAiSettings.builder()
                .userEmail(user)
                .provider(AiProvider.OPENAI_COMPATIBLE.name())
                .model("test-model")
                .baseUrl("http://localhost:59999/v1")
                .apiKeyEncrypted(encrypt("sk-test-123456"))
                .updatedAt(java.time.OffsetDateTime.now())
                .isNew(true)
                .build()).block();

        UserChatClientResolver.ResolvedChat resolved = resolver.resolve(user).block();

        Assertions.assertNotNull(resolved);
        Assertions.assertEquals("OPENAI_COMPATIBLE", resolved.provider());
        Assertions.assertEquals("test-model", resolved.model());
        Assertions.assertNotNull(resolved.chatModel());

        // Cache hit returns the same model instance until settings change
        UserChatClientResolver.ResolvedChat again = resolver.resolve(user).block();
        Assertions.assertSame(resolved.chatModel(), again.chatModel());

        // Anonymous principal always resolves to the server default (mocked model here)
        UserChatClientResolver.ResolvedChat anonymous = resolver.resolve(CONNECTED_USER).block();
        Assertions.assertNotSame(resolved.chatModel(), anonymous.chatModel());
    }

    /** Seed ciphertext for a named user (the REST API only writes the connected principal's row). */
    private String encrypt(String key) {
        return cipherBean.encrypt(key);
    }

    @Autowired
    org.openfilz.dms.service.impl.AiSettingsCipher cipherBean;
}
