package org.openfilz.dms.e2e;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.Settings;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
 * The chat is on but no chat model exists anywhere: no deployment model ({@code spring.ai.model.chat=none},
 * no {@code ChatModel} bean), no runtime model, no personal settings. The chat endpoint answers
 * {@code 409 {"code":"AI_NO_MODEL","userKeysAllowed":true}} before its first event (never a 500, never an
 * error event inside a 200 stream), and {@code Settings.aiModelSource} says {@code NONE}.
 * <p>
 * Embeddings are a deterministic in-memory model (the chat service needs a vector store to be built);
 * runs under no-auth, so the caller has no personal settings row.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import(AiNoModelIT.EmbeddingsOnly.class)
class AiNoModelIT extends TestContainersBaseConfig {

    private static final String AI_PREFIX = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_AI;
    private static final String SETTINGS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS;

    AiNoModelIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    /** An embedding model and a vector store, and no chat model. */
    @TestConfiguration
    static class EmbeddingsOnly {
        @Bean
        @Primary
        EmbeddingModel noChatEmbeddingModel() {
            return new AiTestConfig.BagOfWordsEmbeddingModel();
        }

        @Bean
        @Primary
        VectorStore noChatVectorStore(EmbeddingModel noChatEmbeddingModel) {
            return SimpleVectorStore.builder(noChatEmbeddingModel).build();
        }
    }

    @DynamicPropertySource
    static void aiOnNoModel(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        registry.add("openfilz.ai.chat.active", () -> true);
        registry.add("openfilz.ai.user-settings.enabled", () -> true);
        registry.add("openfilz.ai.user-settings.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
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
    void chat_answers409AiNoModel_beforeTheStreamStarts() {
        getWebTestClient().post().uri(AI_PREFIX + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\":\"hello\"}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("AI_NO_MODEL")
                .jsonPath("$.userKeysAllowed").isEqualTo(true);
    }

    @Test
    void settings_reportNoModelSource_andKeepTheChatOfferedWithPersonalKeys() {
        Settings settings = getWebTestClient().get().uri(SETTINGS)
                .exchange().expectStatus().isOk()
                .expectBody(Settings.class).returnResult().getResponseBody();

        assertThat(settings).isNotNull();
        assertThat(settings.aiModelSource()).isEqualTo("NONE");
        // Unchanged meaning: with personal keys allowed the chat stays offered.
        assertThat(settings.aiChatActive()).isTrue();
        assertThat(settings.aiUserSettingsEnabled()).isTrue();
    }
}
