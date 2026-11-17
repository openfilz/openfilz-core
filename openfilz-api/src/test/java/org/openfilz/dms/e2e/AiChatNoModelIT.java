package org.openfilz.dms.e2e;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.Settings;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * AI on, the chat switch on, but <b>no chat model</b> — what {@code OPENFILZ_AI_MODEL=openfilz-cloud:default}
 * produces (the gateway serves insights and smart filing only, so the chat selector is {@code none}).
 * <p>
 * Before {@code Settings.aiChatUnavailableReason}, {@code aiChatActive} was true here and the web app
 * offered a chat that failed on its first message. The settings must now report the chat as
 * unavailable with {@code NO_MODEL}. Per-user BYOK is off here: when users can bring their own key the
 * chat stays offered without a server model ({@code SettingsServiceImplTest}).
 * <p>
 * Like {@link AiChatDisabledIT}, no {@code AiTestConfig}: every selector is {@code none}, so the
 * context has no {@code ChatModel} bean. The selectors are set explicitly because
 * {@code @DynamicPropertySource} values reach the environment after the post-processor has run.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import(AiNoModelIT.EmbeddingsOnly.class) // the chat service needs a vector store to be built
class AiChatNoModelIT extends TestContainersBaseConfig {

    private static final String SETTINGS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS;

    AiChatNoModelIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void aiOnChatOnNoModel(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        registry.add("openfilz.ai.chat.active", () -> true);
        registry.add("openfilz.ai.user-settings.enabled", () -> false); // with per-user BYOK the chat stays offered
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

    @Test
    void settings_reportTheChatUnavailableForLackOfAModel() {
        Settings settings = getWebTestClient().get().uri(SETTINGS)
                .exchange().expectStatus().isOk()
                .expectBody(Settings.class).returnResult().getResponseBody();

        assertThat(settings).isNotNull();
        assertThat(settings.aiActive()).isTrue();
        assertThat(settings.aiChatActive()).isFalse();
        assertThat(settings.aiChatUnavailableReason()).isEqualTo("NO_MODEL");
        assertThat(settings.aiUserSettingsEnabled()).isFalse();
        assertThat(settings.aiModelSource()).isEqualTo("NONE");
    }

    /** A chat request anyway (an older page, a model removed meanwhile): 409, personal keys not allowed. */
    @Test
    void chat_answers409AiNoModel_withoutPersonalKeys() {
        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_AI + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\":\"hello\"}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("AI_NO_MODEL")
                .jsonPath("$.userKeysAllowed").isEqualTo(false);
    }
}
