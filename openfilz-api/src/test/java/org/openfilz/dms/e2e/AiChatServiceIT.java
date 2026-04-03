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
import org.springframework.http.codec.json.Jackson2JsonEncoder;
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

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private AiChatConversationRepository conversationRepository;

    @Autowired
    private AiChatMessageRepository messageRepository;

    @Autowired
    private DatabaseClient databaseClient;

    public AiChatServiceIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @DynamicPropertySource
    static void configureAiProperties(DynamicPropertyRegistry registry) {
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

        List<AiChatResponse> responses = aiChatService.chat(request)
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

        List<AiChatResponse> firstResponses = aiChatService.chat(request1)
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

        aiChatService.chat(request2).collectList().block();

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

        StepVerifier.create(aiChatService.chat(request))
                .thenConsumeWhile(r -> r.getType() != AiChatResponse.EventType.DONE)
                .expectNextMatches(r -> r.getType() == AiChatResponse.EventType.DONE)
                .verifyComplete();
    }

    @Test
    void chat_streamMessageEventsHaveContent() {
        AiChatRequest request = AiChatRequest.builder()
                .message("Tell me something")
                .build();

        List<AiChatResponse> responses = aiChatService.chat(request)
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
        StepVerifier.create(aiChatService.listConversations())
                .verifyComplete();
    }

    @Test
    void listConversations_afterChat_returnsConversation() {
        // Create a conversation
        AiChatRequest request = AiChatRequest.builder()
                .message("Create conversation for listing")
                .build();
        aiChatService.chat(request).collectList().block();

        List<AiChatConversation> conversations = aiChatService.listConversations()
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
        aiChatService.chat(request1).collectList().block();

        // Small delay for ordering
        AiChatRequest request2 = AiChatRequest.builder().message("Newer conversation").build();
        aiChatService.chat(request2).collectList().block();

        List<AiChatConversation> conversations = aiChatService.listConversations()
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

        List<AiChatResponse> chatResponses = aiChatService.chat(request)
                .collectList()
                .block();

        UUID conversationId = chatResponses.stream()
                .filter(r -> r.getConversationId() != null)
                .findFirst()
                .map(AiChatResponse::getConversationId)
                .orElseThrow();

        List<AiChatResponse> history = aiChatService.getConversationHistory(conversationId)
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
    void getConversationHistory_nonExistent_returnsEmpty() {
        StepVerifier.create(aiChatService.getConversationHistory(UUID.randomUUID()))
                .verifyComplete();
    }

    @Test
    void getConversationHistory_orderedByCreatedAt() {
        AiChatRequest request1 = AiChatRequest.builder()
                .message("First message in conversation")
                .build();

        List<AiChatResponse> responses = aiChatService.chat(request1)
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
        aiChatService.chat(request2).collectList().block();

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

        List<AiChatResponse> responses = aiChatService.chat(request)
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
        aiChatService.deleteConversation(conversationId).block();

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
    void deleteConversation_nonExistent_doesNotThrow() {
        // Should not throw for non-existent ID
        StepVerifier.create(aiChatService.deleteConversation(UUID.randomUUID()))
                .verifyComplete();
    }

    // ========================= Conversation timestamp updates =========================

    @Test
    void chat_updatesConversationTimestamp() {
        AiChatRequest request1 = AiChatRequest.builder()
                .message("Initial message")
                .build();

        List<AiChatResponse> responses = aiChatService.chat(request1)
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

        aiChatService.chat(request2).collectList().block();

        OffsetDateTime secondUpdatedAt = conversationRepository.findById(conversationId).block().getUpdatedAt();

        Assertions.assertFalse(secondUpdatedAt.isBefore(firstUpdatedAt),
                "Updated timestamp should advance after second message");
    }
}
