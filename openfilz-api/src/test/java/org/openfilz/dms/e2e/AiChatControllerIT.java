package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
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

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Integration tests for AiChatController REST endpoints.
 * Tests conversation CRUD operations and chat endpoint accessibility.
 * Uses mock Spring AI beans (no real LLM required).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
@Import(AiTestConfig.class)
public class AiChatControllerIT extends TestContainersBaseConfig {

    private static final String AI_PREFIX = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_AI;

    @Autowired
    protected DatabaseClient databaseClient;

    public AiChatControllerIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @DynamicPropertySource
    static void configureAiProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        // Disable all real model auto-configuration
        registry.add("spring.ai.ollama.chat.enabled", () -> false);
        registry.add("spring.ai.ollama.embedding.enabled", () -> false);
        registry.add("spring.ai.openai.chat.enabled", () -> false);
        registry.add("spring.ai.openai.embedding.enabled", () -> false);
        registry.add("spring.ai.vectorstore.pgvector.initialize-schema", () -> false);
    }

    @BeforeEach
    void cleanConversations() {
        databaseClient.sql("DELETE FROM ai_chat_messages").then().block();
        databaseClient.sql("DELETE FROM ai_chat_conversations").then().block();
    }

    // ========================= Chat Endpoint =========================

    @Test
    void whenChatWithValidMessage_thenStreamReturnsResponse() {
        AiChatRequest request = AiChatRequest.builder()
                .message("What documents do I have?")
                .build();

        List<AiChatResponse> responses = getWebTestClient().post()
                .uri(AI_PREFIX + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AiChatResponse.class)
                .getResponseBody()
                .collectList()
                .block();

        Assertions.assertNotNull(responses);
        Assertions.assertFalse(responses.isEmpty(), "Should receive at least one response chunk");

        // Should have at least a DONE event
        boolean hasDone = responses.stream()
                .anyMatch(r -> r.getType() == AiChatResponse.EventType.DONE);
        Assertions.assertTrue(hasDone, "Should receive a DONE event");

        // All responses should have a conversation ID
        UUID conversationId = responses.getFirst().getConversationId();
        Assertions.assertNotNull(conversationId, "Response should contain conversation ID");

        responses.forEach(r -> {
            if (r.getConversationId() != null) {
                Assertions.assertEquals(conversationId, r.getConversationId(),
                        "All responses should have the same conversation ID");
            }
        });
    }

    @Test
    void whenChatWithExistingConversation_thenUseSameConversationId() {
        // First message creates a new conversation
        AiChatRequest request1 = AiChatRequest.builder()
                .message("Hello, tell me about my files")
                .build();

        List<AiChatResponse> firstResponses = getWebTestClient().post()
                .uri(AI_PREFIX + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request1))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AiChatResponse.class)
                .getResponseBody()
                .collectList()
                .block();

        UUID conversationId = firstResponses.getFirst().getConversationId();
        Assertions.assertNotNull(conversationId);

        // Second message uses existing conversation
        AiChatRequest request2 = AiChatRequest.builder()
                .message("Can you search for PDFs?")
                .conversationId(conversationId)
                .build();

        List<AiChatResponse> secondResponses = getWebTestClient().post()
                .uri(AI_PREFIX + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request2))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AiChatResponse.class)
                .getResponseBody()
                .collectList()
                .block();

        Assertions.assertNotNull(secondResponses);
        secondResponses.stream()
                .filter(r -> r.getConversationId() != null)
                .forEach(r -> Assertions.assertEquals(conversationId, r.getConversationId(),
                        "Second request should use the same conversation ID"));
    }

    @Test
    void whenChatWithBlankMessage_thenBadRequest() {
        AiChatRequest request = AiChatRequest.builder()
                .message("")
                .build();

        getWebTestClient().post()
                .uri(AI_PREFIX + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void whenChatWithNullMessage_thenBadRequest() {
        AiChatRequest request = AiChatRequest.builder()
                .build();

        getWebTestClient().post()
                .uri(AI_PREFIX + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ========================= Conversations CRUD =========================

    @Test
    void whenListConversations_thenReturnsEmptyList() {
        List<AiChatConversation> conversations = getWebTestClient().get()
                .uri(AI_PREFIX + "/conversations")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<AiChatConversation>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(conversations);
        Assertions.assertTrue(conversations.isEmpty());
    }

    @Test
    void whenChatThenListConversations_thenConversationExists() {
        // Create a conversation via chat
        AiChatRequest request = AiChatRequest.builder()
                .message("Create a conversation for me")
                .build();

        getWebTestClient().post()
                .uri(AI_PREFIX + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AiChatResponse.class)
                .getResponseBody()
                .collectList()
                .block();

        // List conversations
        List<AiChatConversation> conversations = getWebTestClient().get()
                .uri(AI_PREFIX + "/conversations")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<AiChatConversation>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(conversations);
        Assertions.assertEquals(1, conversations.size());
        Assertions.assertNotNull(conversations.getFirst().getId());
        Assertions.assertNotNull(conversations.getFirst().getTitle());
        Assertions.assertTrue(conversations.getFirst().getTitle().contains("Create a conversation"));
    }

    @Test
    void whenGetConversationHistory_thenReturnsMessages() {
        // Create a conversation via chat
        AiChatRequest request = AiChatRequest.builder()
                .message("Tell me about document management")
                .build();

        List<AiChatResponse> chatResponses = getWebTestClient().post()
                .uri(AI_PREFIX + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AiChatResponse.class)
                .getResponseBody()
                .collectList()
                .block();

        UUID conversationId = chatResponses.getFirst().getConversationId();

        // Get history
        List<AiChatResponse> history = getWebTestClient().get()
                .uri(AI_PREFIX + "/conversations/" + conversationId)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<AiChatResponse>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(history);
        // Should have at least user message + assistant response
        Assertions.assertTrue(history.size() >= 2,
                "History should contain user message and assistant response, got " + history.size());
    }

    @Test
    void whenGetHistoryOfNonExistentConversation_thenReturnsEmptyList() {
        UUID randomId = UUID.randomUUID();

        List<AiChatResponse> history = getWebTestClient().get()
                .uri(AI_PREFIX + "/conversations/" + randomId)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<AiChatResponse>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(history);
        Assertions.assertTrue(history.isEmpty());
    }

    @Test
    void whenDeleteConversation_thenConversationRemoved() {
        // Create a conversation via chat
        AiChatRequest request = AiChatRequest.builder()
                .message("This conversation will be deleted")
                .build();

        List<AiChatResponse> chatResponses = getWebTestClient().post()
                .uri(AI_PREFIX + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AiChatResponse.class)
                .getResponseBody()
                .collectList()
                .block();

        UUID conversationId = chatResponses.getFirst().getConversationId();

        // Delete it
        getWebTestClient().delete()
                .uri(AI_PREFIX + "/conversations/" + conversationId)
                .exchange()
                .expectStatus().isOk();

        // Verify it's gone
        List<AiChatConversation> conversations = getWebTestClient().get()
                .uri(AI_PREFIX + "/conversations")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<AiChatConversation>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(conversations);
        Assertions.assertTrue(conversations.stream()
                .noneMatch(c -> c.getId().equals(conversationId)));
    }

    @Test
    void whenDeleteConversation_thenMessagesAlsoCascadeDeleted() {
        // Create a conversation with messages
        AiChatRequest request = AiChatRequest.builder()
                .message("Messages should cascade delete")
                .build();

        List<AiChatResponse> chatResponses = getWebTestClient().post()
                .uri(AI_PREFIX + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AiChatResponse.class)
                .getResponseBody()
                .collectList()
                .block();

        UUID conversationId = chatResponses.getFirst().getConversationId();

        // Verify messages exist before delete
        Long messageCountBefore = databaseClient
                .sql("SELECT COUNT(*) FROM ai_chat_messages WHERE conversation_id = :id")
                .bind("id", conversationId)
                .map(row -> row.get(0, Long.class))
                .one()
                .block();
        Assertions.assertTrue(messageCountBefore > 0, "Messages should exist before delete");

        // Delete conversation
        getWebTestClient().delete()
                .uri(AI_PREFIX + "/conversations/" + conversationId)
                .exchange()
                .expectStatus().isOk();

        // Verify messages are cascade deleted
        Long messageCountAfter = databaseClient
                .sql("SELECT COUNT(*) FROM ai_chat_messages WHERE conversation_id = :id")
                .bind("id", conversationId)
                .map(row -> row.get(0, Long.class))
                .one()
                .block();
        Assertions.assertEquals(0L, messageCountAfter, "Messages should be cascade deleted");
    }

    @Test
    void whenMultipleConversations_thenAllListed() {
        // Create first conversation
        AiChatRequest request1 = AiChatRequest.builder()
                .message("First conversation topic")
                .build();

        getWebTestClient().post()
                .uri(AI_PREFIX + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request1))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AiChatResponse.class)
                .getResponseBody()
                .collectList()
                .block();

        // Create second conversation
        AiChatRequest request2 = AiChatRequest.builder()
                .message("Second conversation topic")
                .build();

        getWebTestClient().post()
                .uri(AI_PREFIX + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request2))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AiChatResponse.class)
                .getResponseBody()
                .collectList()
                .block();

        // List all
        List<AiChatConversation> conversations = getWebTestClient().get()
                .uri(AI_PREFIX + "/conversations")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<AiChatConversation>>() {})
                .returnResult().getResponseBody();

        Assertions.assertNotNull(conversations);
        Assertions.assertEquals(2, conversations.size());
    }

    @Test
    void whenConversationTitleLong_thenTruncatedTo100Chars() {
        String longMessage = "A".repeat(200);
        AiChatRequest request = AiChatRequest.builder()
                .message(longMessage)
                .build();

        List<AiChatResponse> responses = getWebTestClient().post()
                .uri(AI_PREFIX + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AiChatResponse.class)
                .getResponseBody()
                .collectList()
                .block();

        UUID conversationId = responses.getFirst().getConversationId();

        List<AiChatConversation> conversations = getWebTestClient().get()
                .uri(AI_PREFIX + "/conversations")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<AiChatConversation>>() {})
                .returnResult().getResponseBody();

        AiChatConversation conv = conversations.stream()
                .filter(c -> c.getId().equals(conversationId))
                .findFirst().orElseThrow();

        // Title should be truncated to 100 chars + "..."
        Assertions.assertTrue(conv.getTitle().length() <= 103,
                "Title should be truncated, was: " + conv.getTitle().length());
        Assertions.assertTrue(conv.getTitle().endsWith("..."));
    }
}
