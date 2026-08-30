package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.ListAiModelsRequest;
import org.openfilz.dms.dto.request.SaveAiSettingsRequest;
import org.openfilz.dms.dto.response.AiConnectionTestResult;
import org.openfilz.dms.dto.response.AiModelsResponse;
import org.openfilz.dms.dto.response.AiSettingsResponse;
import org.openfilz.dms.enums.AiProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Authorization of the per-user AI settings API (BYOK) <b>through the real security chain</b>:
 * Keycloak-issued tokens, {@code openfilz.security.no-auth=false}, every request routed by
 * {@code AbstractSecurityService.authorize}.
 * <p>
 * This exists because {@link AiSettingsControllerIT} runs in <b>no-auth</b> mode, so it exercises
 * the controller and the service but never the role dispatcher — which is exactly how these
 * endpoints shipped unreachable. {@code /api/v1/settings/ai} sits under {@code /settings}, not
 * under {@code /ai}, so it missed the AI branch and fell through to the generic rules: only GET
 * matched one (isQueryOrSearch), PUT and POST matched nothing and were denied, and DELETE was
 * captured by the blanket DELETE-to-CLEANER rule. A READER could read their AI settings but could
 * not save a key, test it, list models, or reset it — "Test connection" answered 403 in the browser.
 * <p>
 * Every assertion is a status assertion on purpose: the point is which caller the chain lets
 * through, not what the service then computes. Note that a 403 here has two possible sources —
 * the chain, and {@code AiSettingsServiceImpl.requireEnabled()} when BYOK itself is off — so this
 * class pins {@code openfilz.ai.user-settings.enabled=true} and any 403 it sees is the chain's.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
@Import(AiTestConfig.class)
public class AiSettingsSecurityIT extends TestContainersKeyCloakConfig {

    private static final String AI_SETTINGS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS + "/ai";

    @Autowired
    private DatabaseClient databaseClient;

    /** No realm roles at all — must never reach the API. */
    private String noRoleToken;
    /** READER only: the profile that hit the 403 in production. */
    private String readerToken;
    private String contributorToken;
    /** CLEANER only: held DELETE under the old generic rule; must not lose it. */
    private String cleanerToken;
    /** AUDITOR only: a real role, but not one that grants web access. */
    private String auditorToken;

    public AiSettingsSecurityIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> keycloak.getAuthServerUrl() + "/realms/openfilz/protocol/openid-connect/certs");
        registry.add("openfilz.security.no-auth", () -> false);
        registry.add("openfilz.ai.active", () -> true);
        registry.add("openfilz.ai.user-settings.enabled", () -> true);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        registry.add("openfilz.ai.user-settings.encryption-key", () -> Base64.getEncoder().encodeToString(key));
        registry.add("spring.ai.openai.api-key", () -> "test-dummy-key");
        // Pin every selector to "none" so AiTestConfig's mocks are the only model beans (see AiSecurityIT).
        registry.add("spring.ai.model.chat", () -> "none");
        registry.add("spring.ai.model.embedding", () -> "none");
        registry.add("spring.ai.model.image", () -> "none");
        registry.add("spring.ai.model.moderation", () -> "none");
        registry.add("spring.ai.model.audio.speech", () -> "none");
        registry.add("spring.ai.model.audio.transcription", () -> "none");
        registry.add("spring.ai.vectorstore.pgvector.initialize-schema", () -> false);
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration");
    }

    @BeforeEach
    void setup() {
        noRoleToken = getAccessToken("test-user");
        readerToken = getAccessToken("reader-user");
        contributorToken = getAccessToken("contributor-user");
        cleanerToken = getAccessToken("cleaner-user");
        auditorToken = getAccessToken("audit-user");

        databaseClient.sql("DELETE FROM user_ai_settings").then().block();
    }

    /**
     * OPENAI_COMPATIBLE against a closed local port: the provider call is really attempted (so the
     * request travels the whole controller-to-service path a 403 would have cut short) without
     * ever leaving the machine.
     */
    private SaveAiSettingsRequest saveRequest(String apiKey) {
        return new SaveAiSettingsRequest(AiProvider.OPENAI_COMPATIBLE, "test-model",
                "http://localhost:59999/v1", apiKey);
    }

    private WebTestClient.ResponseSpec get(String token) {
        return getWebTestClient().get().uri(AI_SETTINGS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private WebTestClient.ResponseSpec put(String token) {
        return getWebTestClient().put().uri(AI_SETTINGS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(saveRequest("sk-test-123456")))
                .exchange();
    }

    private WebTestClient.ResponseSpec testConnection(String token) {
        return getWebTestClient().post().uri(AI_SETTINGS + "/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(saveRequest("sk-test-123456")))
                .exchange();
    }

    private WebTestClient.ResponseSpec listModels(String token) {
        return getWebTestClient().post().uri(AI_SETTINGS + "/models")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(new ListAiModelsRequest(
                        AiProvider.OPENAI_COMPATIBLE, "http://localhost:59999/v1", "sk-test-123456")))
                .exchange();
    }

    private WebTestClient.ResponseSpec delete(String token) {
        return getWebTestClient().delete().uri(AI_SETTINGS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    // ===================== the regression: a READER must reach all five =====================

    @Test
    void readerMaySaveTheirOwnKey() {
        AiSettingsResponse saved = put(readerToken)
                .expectStatus().isOk()
                .expectBody(AiSettingsResponse.class)
                .returnResult().getResponseBody();

        assertNotNull(saved);
        assertEquals("OPENAI_COMPATIBLE", saved.provider());
        assertTrue(saved.hasApiKey());
    }

    /**
     * The exact call behind the reported bug: saving worked, "Test connection" answered 403.
     * A reachable endpoint would need a real provider, so the assertion is that the request got
     * <em>through</em> — the graceful ok=false body proves the service ran rather than the chain
     * rejecting the call.
     */
    @Test
    void readerMayTestTheirConnection() {
        AiConnectionTestResult result = testConnection(readerToken)
                .expectStatus().isOk()
                .expectBody(AiConnectionTestResult.class)
                .returnResult().getResponseBody();

        assertNotNull(result);
        assertFalse(result.ok(), "unreachable endpoint is a failed test, not an authorization error");
        assertNotNull(result.message());
    }

    /**
     * The model picker calls this on every provider/key change and swallows its errors, so a 403
     * here was invisible in the UI — the dropdown just kept showing the stored model. The reply is
     * FALLBACK because the endpoint is unreachable, and empty because {@code OPENAI_COMPATIBLE}
     * has no built-in catalog (an arbitrary endpoint has no known models); what matters is that
     * the service answered at all.
     */
    @Test
    void readerMayListProviderModels() {
        AiModelsResponse response = listModels(readerToken)
                .expectStatus().isOk()
                .expectBody(AiModelsResponse.class)
                .returnResult().getResponseBody();

        assertNotNull(response);
        assertEquals(AiProvider.OPENAI_COMPATIBLE, response.provider());
        assertEquals(AiModelsResponse.Source.FALLBACK, response.source());
        assertNotNull(response.message());
    }

    @Test
    void readerMayReadTheirOwnSettings() {
        get(readerToken).expectStatus().isOk();
    }

    /** Reset was captured by the blanket DELETE rule, so a READER could not undo their own key. */
    @Test
    void readerMayResetTheirOwnSettings() {
        put(readerToken).expectStatus().isOk();

        delete(readerToken).expectStatus().isOk();

        AiSettingsResponse after = get(readerToken)
                .expectStatus().isOk()
                .expectBody(AiSettingsResponse.class)
                .returnResult().getResponseBody();
        assertNotNull(after);
        assertNull(after.provider());
        assertFalse(after.hasApiKey());
    }

    @Test
    void contributorMaySaveAndTest() {
        put(contributorToken).expectStatus().isOk();
        testConnection(contributorToken).expectStatus().isOk();
    }

    /** The branch widened the old rule rather than replacing it: CLEANER keeps its DELETE. */
    @Test
    void cleanerKeepsReset() {
        delete(cleanerToken).expectStatus().isOk();
    }

    // ===================== and nobody else gets in =====================

    @Test
    void everyMethodRequiresAuthentication() {
        getWebTestClient().get().uri(AI_SETTINGS)
                .exchange().expectStatus().isUnauthorized();
        getWebTestClient().put().uri(AI_SETTINGS)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(saveRequest("sk-test-123456")))
                .exchange().expectStatus().isUnauthorized();
        getWebTestClient().post().uri(AI_SETTINGS + "/test")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(saveRequest("sk-test-123456")))
                .exchange().expectStatus().isUnauthorized();
        getWebTestClient().delete().uri(AI_SETTINGS)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void tokenWithoutAnyRoleIsRefused() {
        get(noRoleToken).expectStatus().isForbidden();
        put(noRoleToken).expectStatus().isForbidden();
        testConnection(noRoleToken).expectStatus().isForbidden();
        listModels(noRoleToken).expectStatus().isForbidden();
        delete(noRoleToken).expectStatus().isForbidden();
    }

    /** AUDITOR is not web access — the fix must not have opened these to every authenticated user. */
    @Test
    void auditorOnlyTokenIsRefused() {
        get(auditorToken).expectStatus().isForbidden();
        put(auditorToken).expectStatus().isForbidden();
        testConnection(auditorToken).expectStatus().isForbidden();
    }
}
