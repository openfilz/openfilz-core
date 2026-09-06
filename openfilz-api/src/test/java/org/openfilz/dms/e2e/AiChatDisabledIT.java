package org.openfilz.dms.e2e;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.Settings;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * The chat kill switch: {@code openfilz.ai.active=true} with {@code openfilz.ai.chat.active=false}.
 * <p>
 * This is the "light" deployment shape — the automatic AI features (embeddings, insights, smart
 * filing, the by-kind reorganisation) run while <b>no chat model exists at all</b>. The suite
 * therefore deliberately does <em>not</em> import {@code AiTestConfig}: every model selector is
 * {@code none}, so the context has no {@code ChatModel} bean, which is exactly the configuration
 * that used to be impossible ({@code UserChatClientResolver} required the bean, and with it
 * {@code AiDocumentInsightService} / {@code DefaultAutoFileService}).
 * <p>
 * What it pins:
 * <ul>
 *   <li>the chat and BYOK endpoints answer 404 — the same shape as a deployment without the feature;</li>
 *   <li>{@code Settings.aiActive} stays true while {@code aiChatActive} goes false, so the frontend
 *       hides the chat button and "Organise with AI" and keeps everything else;</li>
 *   <li>the model-free reorganisation still answers, proving the rest of the AI surface is alive.</li>
 * </ul>
 * Runs under no-auth, so a 404 can only come from the toggle, never from the security chain.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class AiChatDisabledIT extends TestContainersBaseConfig {

    private static final String AI_PREFIX = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_AI;
    private static final String SETTINGS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS;

    AiChatDisabledIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void aiOnChatOff(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        registry.add("openfilz.ai.chat.active", () -> false);
        registry.add("openfilz.ai.user-settings.enabled", () -> true); // still hidden: BYOK follows the chat
        // No model of any kind — the point of the suite.
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
    void chatEndpoints_answer404() {
        getWebTestClient().post().uri(AI_PREFIX + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\":\"hello\"}")
                .exchange().expectStatus().isNotFound();

        getWebTestClient().get().uri(AI_PREFIX + "/conversations").exchange().expectStatus().isNotFound();
        getWebTestClient().get().uri(AI_PREFIX + "/conversations/" + UUID.randomUUID())
                .exchange().expectStatus().isNotFound();
    }

    /** BYOK only ever overrides the chat model, so it goes with the chat. */
    @Test
    void byokEndpoints_answer404() {
        getWebTestClient().get().uri(SETTINGS + "/ai").exchange().expectStatus().isNotFound();
    }

    @Test
    void settings_reportTheAiFeatureOnAndTheChatOff() {
        Settings settings = getWebTestClient().get().uri(SETTINGS)
                .exchange().expectStatus().isOk()
                .expectBody(Settings.class).returnResult().getResponseBody();

        assertThat(settings).isNotNull();
        assertThat(settings.aiActive()).isTrue();
        assertThat(settings.aiChatActive()).isFalse();
        assertThat(settings.aiUserSettingsEnabled()).isFalse();
    }

    /**
     * The rest of the AI surface must stay reachable — and it must work with no {@code ChatModel}
     * bean in the context, which is the whole point of the light profile.
     */
    @Test
    void modelFreeReorganization_stillAnswers() {
        getWebTestClient().post().uri(AI_PREFIX + "/reorganization/by-kind")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange().expectStatus().isOk();
    }
}
