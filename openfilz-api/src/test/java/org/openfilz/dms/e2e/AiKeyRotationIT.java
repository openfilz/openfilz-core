package org.openfilz.dms.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.SaveAiSettingsRequest;
import org.openfilz.dms.dto.response.AiSettingsResponse;
import org.openfilz.dms.enums.AiProvider;
import org.openfilz.dms.service.ai.UserAiSettingsReencryption;
import org.openfilz.dms.service.ai.UserChatClientResolver;
import org.openfilz.dms.service.impl.AiSettingsCipher;
import org.openfilz.dms.utils.SecretKeyring;
import org.openfilz.dms.utils.SecretReencryptionPass;
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

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Rotation of {@code AI_SETTINGS_ENCRYPTION_KEY}: a key stored under the previous key (old format, as every
 * deployment holds today) still works, the start-up pass rewrites it under the current key with a
 * conditional update, a second pass changes nothing, and a key that no configured key opens is left alone
 * and answered 409 {@code AI_KEY_UNREADABLE} by Settings &gt; AI. No-auth mode: the user is "anonymousUser".
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import(AiTestConfig.class)
public class AiKeyRotationIT extends TestContainersBaseConfig {

    private static final String AI_SETTINGS_PREFIX = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS + "/ai";
    private static final String CONNECTED_USER = "anonymousUser";

    private static final String CURRENT_KEY = randomKey();
    private static final String PREVIOUS_KEY = randomKey();

    @Autowired
    protected DatabaseClient databaseClient;

    @Autowired
    protected UserChatClientResolver resolver;

    @Autowired
    protected AiSettingsCipher cipher;

    @Autowired
    protected UserAiSettingsReencryption reencryption;

    public AiKeyRotationIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @DynamicPropertySource
    static void configureAiProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        registry.add("openfilz.ai.user-settings.enabled", () -> true);
        registry.add("openfilz.ai.user-settings.encryption-key", () -> CURRENT_KEY);
        registry.add("openfilz.ai.user-settings.previous-encryption-keys", () -> PREVIOUS_KEY);
        registry.add("spring.ai.openai.api-key", () -> "test-dummy-key");
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

    /** The format written before key ids, under a given key. */
    private static String legacy(String key, String plaintext) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(key), "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.getEncoder().encodeToString(out);
    }

    private void saveThroughTheApi(String apiKey) {
        getWebTestClient().put().uri(AI_SETTINGS_PREFIX)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(new SaveAiSettingsRequest(AiProvider.OPENAI_COMPATIBLE, "test-model",
                        "http://localhost:59999/v1", apiKey)))
                .exchange()
                .expectStatus().isOk();
    }

    /**
     * Seam: no endpoint writes a value under an old key, which is what a deployment holds before the
     * rotation. The row itself is created through the API.
     */
    private void storeRaw(String value) {
        databaseClient.sql("UPDATE user_ai_settings SET api_key_encrypted = :v WHERE user_email = :u")
                .bind("v", value).bind("u", CONNECTED_USER).then().block();
        resolver.evict(CONNECTED_USER);
    }

    private String storedValue() {
        return databaseClient.sql("SELECT api_key_encrypted FROM user_ai_settings WHERE user_email = :u")
                .bind("u", CONNECTED_USER).map(row -> row.get(0, String.class)).one().block();
    }

    private AiSettingsResponse getSettings() {
        return getWebTestClient().get().uri(AI_SETTINGS_PREFIX).exchange()
                .expectStatus().isOk()
                .expectBody(AiSettingsResponse.class).returnResult().getResponseBody();
    }

    @Test
    void savedValues_areWrittenUnderTheCurrentKid() {
        saveThroughTheApi("sk-current-key-1111");

        String kid = SecretKeyring.kidOf(Base64.getDecoder().decode(CURRENT_KEY));
        assertThat(storedValue()).startsWith("v2:" + kid + ":");
        assertThat(getSettings().keySuffix()).isEqualTo("1111");
    }

    @Test
    void aKeyUnderThePreviousKey_stillReads_andThePassRewritesItOnce() throws Exception {
        saveThroughTheApi("sk-placeholder");
        storeRaw(legacy(PREVIOUS_KEY, "sk-previous-key-2222"));

        AiSettingsResponse before = getSettings();
        assertThat(before.keyUnreadable()).isFalse();
        assertThat(before.keySuffix()).isEqualTo("2222");

        SecretReencryptionPass.Counts first = reencryption.reencrypt(cipher).block();
        assertThat(first).isEqualTo(new SecretReencryptionPass.Counts(1, 0, 0, 0));
        String rewritten = storedValue();
        assertThat(cipher.isCurrent(rewritten)).isTrue();

        SecretReencryptionPass.Counts second = reencryption.reencrypt(cipher).block();
        assertThat(second).isEqualTo(new SecretReencryptionPass.Counts(0, 1, 0, 0));
        assertThat(storedValue()).isEqualTo(rewritten);

        resolver.evict(CONNECTED_USER);
        assertThat(getSettings().keySuffix()).isEqualTo("2222");
    }

    @Test
    void aKeyNoConfiguredKeyOpens_isLeftAlone_andAnswered409() throws Exception {
        saveThroughTheApi("sk-placeholder");
        String lost = legacy(randomKey(), "sk-lost-key-3333");
        storeRaw(lost);

        assertThat(reencryption.reencrypt(cipher).block()).isEqualTo(new SecretReencryptionPass.Counts(0, 0, 1, 0));
        assertThat(storedValue()).isEqualTo(lost);

        AiSettingsResponse settings = getSettings();
        assertThat(settings.hasApiKey()).isTrue();
        assertThat(settings.keyUnreadable()).isTrue();

        getWebTestClient().post().uri(AI_SETTINGS_PREFIX + "/test")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(new SaveAiSettingsRequest(AiProvider.OPENAI_COMPATIBLE, "test-model",
                        "http://localhost:59999/v1", null)))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("AI_KEY_UNREADABLE");

        // Entering the key again repairs it
        saveThroughTheApi("sk-new-key-4444");
        assertThat(getSettings().keyUnreadable()).isFalse();
    }
}
