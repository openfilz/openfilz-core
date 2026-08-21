package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.request.AiChatRequest;
import org.openfilz.dms.dto.response.AiChatResponse;
import org.openfilz.dms.entity.AiChatConversation;
import org.openfilz.dms.entity.AiChatMessage;
import org.openfilz.dms.repository.AiChatConversationRepository;
import org.openfilz.dms.repository.AiChatMessageRepository;
import org.openfilz.dms.service.AiChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Integration tests for AiChatService.
 * Tests conversation lifecycle, message persistence, and service behavior.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
@Import(AiTestConfig.class)
public class AiChatServiceIT extends TestContainersBaseConfig {

    /** Service calls are user-scoped now; ITs run with security no-auth, matching the anonymous principal. */
    private static final String TEST_USER = "anonymousUser";

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private AiChatConversationRepository conversationRepository;

    @Autowired
    private AiChatMessageRepository messageRepository;

    @Autowired
    private DatabaseClient databaseClient;

    public AiChatServiceIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void configureAiProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        registry.add("spring.ai.openai.api-key", () -> "test-dummy-key");
        // Spring AI 2.0 dropped the per-provider spring.ai.<provider>.<kind>.enabled flags: provider
        // auto-configuration is now gated on spring.ai.model.*, and those conditions match if the
        // property is missing. Pinning every selector to "none" keeps the real Ollama/OpenAI models
        // out of the context so AiTestConfig's mocks are the only ChatModel/EmbeddingModel beans.
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
    void cleanDb() {
        databaseClient.sql("DELETE FROM ai_chat_messages").then().block();
        databaseClient.sql("DELETE FROM ai_chat_conversations").then().block();
    }

    // ========================= chat() =========================

    @Test
    void chat_newConversation_createsConversationAndMessages() {
        AiChatRequest request = AiChatRequest.builder()
                .message("Hello AI, help me with documents")
                .build();

        List<AiChatResponse> responses = aiChatService.chat(request, TEST_USER)
                .collectList()
                .block();

        Assertions.assertNotNull(responses);
        Assertions.assertFalse(responses.isEmpty());

        // Extract conversation ID
        UUID conversationId = responses.stream()
                .filter(r -> r.getConversationId() != null)
                .findFirst()
                .map(AiChatResponse::getConversationId)
                .orElseThrow();

        // Verify conversation was persisted
        AiChatConversation conversation = conversationRepository.findById(conversationId).block();
        Assertions.assertNotNull(conversation);
        Assertions.assertTrue(conversation.getTitle().contains("Hello AI"));

        // Verify messages were persisted
        List<AiChatMessage> messages = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversationId)
                .collectList()
                .block();

        Assertions.assertNotNull(messages);
        Assertions.assertTrue(messages.size() >= 2,
                "Should have at least user + assistant messages, got " + messages.size());

        // First message should be USER
        Assertions.assertEquals("USER", messages.getFirst().getRole());
        Assertions.assertEquals("Hello AI, help me with documents", messages.getFirst().getContent());

        // Last message should be ASSISTANT
        AiChatMessage assistantMsg = messages.stream()
                .filter(m -> "ASSISTANT".equals(m.getRole()))
                .findFirst()
                .orElseThrow();
        Assertions.assertNotNull(assistantMsg.getContent());
        Assertions.assertFalse(assistantMsg.getContent().isBlank());
    }

    @Test
    void chat_existingConversation_appendsMessages() {
        // Create first message
        AiChatRequest request1 = AiChatRequest.builder()
                .message("First question about documents")
                .build();

        List<AiChatResponse> firstResponses = aiChatService.chat(request1, TEST_USER)
                .collectList()
                .block();

        UUID conversationId = firstResponses.stream()
                .filter(r -> r.getConversationId() != null)
                .findFirst()
                .map(AiChatResponse::getConversationId)
                .orElseThrow();

        // Send second message to same conversation
        AiChatRequest request2 = AiChatRequest.builder()
                .message("Follow-up question")
                .conversationId(conversationId)
                .build();

        aiChatService.chat(request2, TEST_USER).collectList().block();

        // Verify all messages are persisted
        List<AiChatMessage> messages = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversationId)
                .collectList()
                .block();

        // Should have: user1, assistant1, user2, assistant2
        Assertions.assertTrue(messages.size() >= 4,
                "Should have at least 4 messages for 2-turn conversation, got " + messages.size());

        long userCount = messages.stream().filter(m -> "USER".equals(m.getRole())).count();
        long assistantCount = messages.stream().filter(m -> "ASSISTANT".equals(m.getRole())).count();

        Assertions.assertTrue(userCount >= 2, "Should have at least 2 user messages");
        Assertions.assertTrue(assistantCount >= 2, "Should have at least 2 assistant messages");
    }

    @Test
    void chat_streamContainsDoneEvent() {
        AiChatRequest request = AiChatRequest.builder()
                .message("Quick question")
                .build();

        StepVerifier.create(aiChatService.chat(request, TEST_USER))
                .thenConsumeWhile(r -> r.getType() != AiChatResponse.EventType.DONE)
                .expectNextMatches(r -> r.getType() == AiChatResponse.EventType.DONE)
                .verifyComplete();
    }

    @Test
    void chat_streamMessageEventsHaveContent() {
        AiChatRequest request = AiChatRequest.builder()
                .message("Tell me something")
                .build();

        List<AiChatResponse> responses = aiChatService.chat(request, TEST_USER)
                .collectList()
                .block();

        List<AiChatResponse> messageEvents = responses.stream()
                .filter(r -> r.getType() == AiChatResponse.EventType.MESSAGE)
                .toList();

        // At least one MESSAGE event should have content
        Assertions.assertFalse(messageEvents.isEmpty(), "Should have message events");
        messageEvents.forEach(m ->
                Assertions.assertNotNull(m.getContent(), "MESSAGE events should have content"));
    }

    // ========================= listConversations() =========================

    @Test
    void listConversations_empty_returnsEmptyFlux() {
        StepVerifier.create(aiChatService.listConversations(TEST_USER))
                .verifyComplete();
    }

    @Test
    void listConversations_afterChat_returnsConversation() {
        // Create a conversation
        AiChatRequest request = AiChatRequest.builder()
                .message("Create conversation for listing")
                .build();
        aiChatService.chat(request, TEST_USER).collectList().block();

        List<AiChatConversation> conversations = aiChatService.listConversations(TEST_USER)
                .collectList()
                .block();

        Assertions.assertNotNull(conversations);
        Assertions.assertEquals(1, conversations.size());
        Assertions.assertNotNull(conversations.getFirst().getCreatedAt());
        Assertions.assertNotNull(conversations.getFirst().getUpdatedAt());
    }

    @Test
    void listConversations_orderedByUpdatedAt() {
        // Create two conversations
        AiChatRequest request1 = AiChatRequest.builder().message("Older conversation").build();
        aiChatService.chat(request1, TEST_USER).collectList().block();

        // Small delay for ordering
        AiChatRequest request2 = AiChatRequest.builder().message("Newer conversation").build();
        aiChatService.chat(request2, TEST_USER).collectList().block();

        List<AiChatConversation> conversations = aiChatService.listConversations(TEST_USER)
                .collectList()
                .block();

        Assertions.assertEquals(2, conversations.size());
        // Most recently updated should be first
        Assertions.assertTrue(
                !conversations.get(0).getUpdatedAt().isBefore(conversations.get(1).getUpdatedAt()),
                "Conversations should be ordered by updatedAt DESC");
    }

    // ========================= getConversationHistory() =========================

    @Test
    void getConversationHistory_returnsAllMessages() {
        AiChatRequest request = AiChatRequest.builder()
                .message("History test message")
                .build();

        List<AiChatResponse> chatResponses = aiChatService.chat(request, TEST_USER)
                .collectList()
                .block();

        UUID conversationId = chatResponses.stream()
                .filter(r -> r.getConversationId() != null)
                .findFirst()
                .map(AiChatResponse::getConversationId)
                .orElseThrow();

        List<AiChatResponse> history = aiChatService.getConversationHistory(conversationId, TEST_USER)
                .collectList()
                .block();

        Assertions.assertNotNull(history);
        Assertions.assertTrue(history.size() >= 2);
        history.forEach(h -> {
            Assertions.assertEquals(conversationId, h.getConversationId());
            Assertions.assertNotNull(h.getContent());
            Assertions.assertEquals(AiChatResponse.EventType.MESSAGE, h.getType());
        });
    }

    @Test
    void getConversationHistory_nonExistent_errorsNotFound() {
        StepVerifier.create(aiChatService.getConversationHistory(UUID.randomUUID(), TEST_USER))
                .expectErrorMatches(e -> e instanceof org.springframework.web.server.ResponseStatusException rse
                        && rse.getStatusCode().value() == 404)
                .verify();
    }

    @Test
    void getConversationHistory_orderedByCreatedAt() {
        AiChatRequest request1 = AiChatRequest.builder()
                .message("First message in conversation")
                .build();

        List<AiChatResponse> responses = aiChatService.chat(request1, TEST_USER)
                .collectList()
                .block();

        UUID conversationId = responses.stream()
                .filter(r -> r.getConversationId() != null)
                .findFirst()
                .map(AiChatResponse::getConversationId)
                .orElseThrow();

        // Add second turn
        AiChatRequest request2 = AiChatRequest.builder()
                .message("Second message in conversation")
                .conversationId(conversationId)
                .build();
        aiChatService.chat(request2, TEST_USER).collectList().block();

        List<AiChatMessage> messages = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversationId)
                .collectList()
                .block();

        // Verify ordering
        for (int i = 1; i < messages.size(); i++) {
            Assertions.assertFalse(
                    messages.get(i).getCreatedAt().isBefore(messages.get(i - 1).getCreatedAt()),
                    "Messages should be ordered chronologically");
        }
    }

    // ========================= deleteConversation() =========================

    @Test
    void deleteConversation_removesConversationAndMessages() {
        AiChatRequest request = AiChatRequest.builder()
                .message("This will be deleted")
                .build();

        List<AiChatResponse> responses = aiChatService.chat(request, TEST_USER)
                .collectList()
                .block();

        UUID conversationId = responses.stream()
                .filter(r -> r.getConversationId() != null)
                .findFirst()
                .map(AiChatResponse::getConversationId)
                .orElseThrow();

        // Verify exists before delete
        Assertions.assertNotNull(conversationRepository.findById(conversationId).block());

        // Delete
        aiChatService.deleteConversation(conversationId, TEST_USER).block();

        // Verify deleted
        Assertions.assertNull(conversationRepository.findById(conversationId).block());

        // Messages should be cascade deleted
        List<AiChatMessage> messages = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversationId)
                .collectList()
                .block();
        Assertions.assertTrue(messages.isEmpty(), "Messages should be cascade deleted");
    }

    @Test
    void deleteConversation_nonExistent_errorsNotFound() {
        StepVerifier.create(aiChatService.deleteConversation(UUID.randomUUID(), TEST_USER))
                .expectErrorMatches(e -> e instanceof org.springframework.web.server.ResponseStatusException rse
                        && rse.getStatusCode().value() == 404)
                .verify();
    }

    // ========================= Conversation timestamp updates =========================

    @Test
    void chat_updatesConversationTimestamp() {
        AiChatRequest request1 = AiChatRequest.builder()
                .message("Initial message")
                .build();

        List<AiChatResponse> responses = aiChatService.chat(request1, TEST_USER)
                .collectList()
                .block();

        UUID conversationId = responses.stream()
                .filter(r -> r.getConversationId() != null)
                .findFirst()
                .map(AiChatResponse::getConversationId)
                .orElseThrow();

        OffsetDateTime firstUpdatedAt = conversationRepository.findById(conversationId).block().getUpdatedAt();

        // Second message
        AiChatRequest request2 = AiChatRequest.builder()
                .message("Follow up message")
                .conversationId(conversationId)
                .build();

        aiChatService.chat(request2, TEST_USER).collectList().block();

        OffsetDateTime secondUpdatedAt = conversationRepository.findById(conversationId).block().getUpdatedAt();

        Assertions.assertFalse(secondUpdatedAt.isBefore(firstUpdatedAt),
                "Updated timestamp should advance after second message");
    }

    // ========================= Per-user conversation scoping =========================

    private static final String USER_A = "alice@openfilz.org";
    private static final String USER_B = "bob@openfilz.org";

    private UUID chatAs(String user, String message) {
        List<AiChatResponse> responses = aiChatService.chat(
                        AiChatRequest.builder().message(message).build(), user)
                .collectList()
                .block();
        return responses.stream()
                .filter(r -> r.getConversationId() != null)
                .findFirst()
                .map(AiChatResponse::getConversationId)
                .orElseThrow();
    }

    @Test
    void listConversations_onlyShowsOwnAndLegacy() {
        UUID aliceConv = chatAs(USER_A, "Alice's private conversation");
        chatAs(USER_B, "Bob's private conversation");

        List<AiChatConversation> aliceList = aiChatService.listConversations(USER_A)
                .collectList()
                .block();

        Assertions.assertNotNull(aliceList);
        Assertions.assertTrue(aliceList.stream().anyMatch(c -> c.getId().equals(aliceConv)),
                "Alice should see her own conversation");
        Assertions.assertTrue(aliceList.stream().allMatch(c -> c.getCreatedBy() == null
                        || c.getCreatedBy().equals(USER_A)),
                "Alice must not see Bob's conversations");
    }

    @Test
    void legacyConversationWithoutOwner_isVisibleToEveryone() {
        UUID legacyId = UUID.randomUUID();
        databaseClient.sql("INSERT INTO ai_chat_conversations (id, title, created_by, created_at, updated_at) "
                        + "VALUES (:id, 'legacy pre-ownership conversation', NULL, now(), now())")
                .bind("id", legacyId)
                .then().block();

        List<AiChatConversation> aliceList = aiChatService.listConversations(USER_A).collectList().block();
        List<AiChatConversation> bobList = aiChatService.listConversations(USER_B).collectList().block();

        Assertions.assertTrue(aliceList.stream().anyMatch(c -> c.getId().equals(legacyId)));
        Assertions.assertTrue(bobList.stream().anyMatch(c -> c.getId().equals(legacyId)));
    }

    @Test
    void getConversationHistory_ofAnotherUser_errorsNotFound() {
        UUID aliceConv = chatAs(USER_A, "Alice's history");

        StepVerifier.create(aiChatService.getConversationHistory(aliceConv, USER_B))
                .expectErrorMatches(e -> e instanceof org.springframework.web.server.ResponseStatusException rse
                        && rse.getStatusCode().value() == 404)
                .verify();
    }

    @Test
    void deleteConversation_ofAnotherUser_errorsNotFound() {
        UUID aliceConv = chatAs(USER_A, "Alice's conversation to protect");

        StepVerifier.create(aiChatService.deleteConversation(aliceConv, USER_B))
                .expectErrorMatches(e -> e instanceof org.springframework.web.server.ResponseStatusException rse
                        && rse.getStatusCode().value() == 404)
                .verify();

        Assertions.assertNotNull(conversationRepository.findById(aliceConv).block(),
                "Bob's delete attempt must not remove Alice's conversation");
    }

    @Test
    void continueConversation_ofAnotherUser_errorsNotFound() {
        UUID aliceConv = chatAs(USER_A, "Alice starts a conversation");

        AiChatRequest hijack = AiChatRequest.builder()
                .message("Bob tries to continue it")
                .conversationId(aliceConv)
                .build();

        StepVerifier.create(aiChatService.chat(hijack, USER_B))
                .expectErrorMatches(e -> e instanceof org.springframework.web.server.ResponseStatusException rse
                        && rse.getStatusCode().value() == 404)
                .verify();
    }
}
