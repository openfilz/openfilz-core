package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.AiChatRequest;
import org.openfilz.dms.dto.response.AiChatResponse;
import org.openfilz.dms.entity.AiChatConversation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Integration tests for AI endpoint security with Keycloak authentication.
 * Tests role-based access control for all AI chat endpoints.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
@Import(AiTestConfig.class)
public class AiSecurityIT extends TestContainersKeyCloakConfig {

    private static final String AI_PREFIX = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_AI;

    @Autowired
    private DatabaseClient databaseClient;

    private String noaccessAccessToken;
    private String readerAccessToken;
    private String contributorAccessToken;
    private String cleanerAccessToken;
    private String adminAccessToken;

    public AiSecurityIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> keycloak.getAuthServerUrl() + "/realms/openfilz/protocol/openid-connect/certs");
        registry.add("openfilz.security.no-auth", () -> false);
        registry.add("openfilz.ai.active", () -> true);
        registry.add("spring.ai.ollama.chat.enabled", () -> false);
        registry.add("spring.ai.ollama.embedding.enabled", () -> false);
        registry.add("spring.ai.openai.api-key", () -> "test-dummy-key");
        registry.add("spring.ai.openai.chat.enabled", () -> false);
        registry.add("spring.ai.openai.embedding.enabled", () -> false);
        registry.add("spring.ai.openai.image.enabled", () -> false);
        registry.add("spring.ai.openai.audio.speech.enabled", () -> false);
        registry.add("spring.ai.openai.audio.transcription.enabled", () -> false);
        registry.add("spring.ai.openai.moderation.enabled", () -> false);
        registry.add("spring.ai.vectorstore.pgvector.initialize-schema", () -> false);
        registry.add("spring.autoconfigure.exclude", () -> "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration");
    }

    @BeforeEach
    void setup() {
        noaccessAccessToken = getAccessToken("test-user");
        readerAccessToken = getAccessToken("reader-user");
        contributorAccessToken = getAccessToken("contributor-user");
        cleanerAccessToken = getAccessToken("cleaner-user");
        adminAccessToken = getAccessToken("admin-user");

        databaseClient.sql("DELETE FROM ai_chat_messages").then().block();
        databaseClient.sql("DELETE FROM ai_chat_conversations").then().block();
    }

    // ========================= POST /ai/chat =========================

    @Test
    void chat_withoutAuth_thenUnauthorized() {
        AiChatRequest request = AiChatRequest.builder()
                .message("Test without auth")
                .build();

        getWebTestClient().post()
                .uri(AI_PREFIX + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void chat_withReaderToken_thenAllowed() {
        AiChatRequest request = AiChatRequest.builder()
                .message("Reader asking a question")
                .build();

        List<AiChatResponse> responses = getWebTestClient().post()
                .uri(AI_PREFIX + "/chat")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + readerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AiChatResponse.class)
                .getResponseBody()
                .collectList()
                .block();

        assertNotNull(responses);
        assertFalse(responses.isEmpty());
    }

    @Test
    void chat_withContributorToken_thenAllowed() {
        AiChatRequest request = AiChatRequest.builder()
                .message("Contributor asking a question")
                .build();

        getWebTestClient().post()
                .uri(AI_PREFIX + "/chat")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void chat_withAdminToken_thenAllowed() {
        AiChatRequest request = AiChatRequest.builder()
                .message("Admin asking a question")
                .build();

        getWebTestClient().post()
                .uri(AI_PREFIX + "/chat")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk();
    }

    // ========================= GET /ai/conversations =========================

    @Test
    void listConversations_withoutAuth_thenUnauthorized() {
        getWebTestClient().get()
                .uri(AI_PREFIX + "/conversations")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void listConversations_withReaderToken_thenAllowed() {
        getWebTestClient().get()
                .uri(AI_PREFIX + "/conversations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + readerAccessToken)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void listConversations_withContributorToken_thenAllowed() {
        getWebTestClient().get()
                .uri(AI_PREFIX + "/conversations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk();
    }

    // ========================= GET /ai/conversations/{id} =========================

    @Test
    void getHistory_withoutAuth_thenUnauthorized() {
        getWebTestClient().get()
                .uri(AI_PREFIX + "/conversations/" + UUID.randomUUID())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void getHistory_withReaderToken_thenAllowed() {
        getWebTestClient().get()
                .uri(AI_PREFIX + "/conversations/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + readerAccessToken)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk();
    }

    // ========================= DELETE /ai/conversations/{id} =========================

    @Test
    void deleteConversation_withoutAuth_thenUnauthorized() {
        getWebTestClient().delete()
                .uri(AI_PREFIX + "/conversations/" + UUID.randomUUID())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void deleteConversation_withContributorToken_thenAllowed() {
        // First create a conversation
        AiChatRequest request = AiChatRequest.builder()
                .message("Conversation to delete with auth")
                .build();

        List<AiChatResponse> responses = getWebTestClient().post()
                .uri(AI_PREFIX + "/chat")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AiChatResponse.class)
                .getResponseBody()
                .collectList()
                .block();

        UUID conversationId = responses.stream()
                .filter(r -> r.getConversationId() != null)
                .findFirst()
                .map(AiChatResponse::getConversationId)
                .orElseThrow();

        getWebTestClient().delete()
                .uri(AI_PREFIX + "/conversations/" + conversationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void deleteConversation_withCleanerToken_thenAllowed() {
        getWebTestClient().delete()
                .uri(AI_PREFIX + "/conversations/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + cleanerAccessToken)
                .exchange()
                .expectStatus().isOk();
    }

    // ========================= Multi-turn with auth =========================

    @Test
    void multiTurnChat_withReaderToken_preservesConversation() {
        AiChatRequest request1 = AiChatRequest.builder()
                .message("First question from reader")
                .build();

        List<AiChatResponse> firstResponses = getWebTestClient().post()
                .uri(AI_PREFIX + "/chat")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + readerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request1))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AiChatResponse.class)
                .getResponseBody()
                .collectList()
                .block();

        UUID conversationId = firstResponses.stream()
                .filter(r -> r.getConversationId() != null)
                .findFirst()
                .map(AiChatResponse::getConversationId)
                .orElseThrow();

        // Continue conversation
        AiChatRequest request2 = AiChatRequest.builder()
                .message("Follow-up from reader")
                .conversationId(conversationId)
                .build();

        List<AiChatResponse> secondResponses = getWebTestClient().post()
                .uri(AI_PREFIX + "/chat")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + readerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request2))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AiChatResponse.class)
                .getResponseBody()
                .collectList()
                .block();

        assertNotNull(secondResponses);
        assertFalse(secondResponses.isEmpty());

        // Verify conversation history is accessible
        List<AiChatResponse> history = getWebTestClient().get()
                .uri(AI_PREFIX + "/conversations/" + conversationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + readerAccessToken)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<AiChatResponse>>() {})
                .returnResult().getResponseBody();

        assertNotNull(history);
        assertTrue(history.size() >= 4,
                "Should have at least 4 messages (2 user + 2 assistant), got " + history.size());
    }
}
