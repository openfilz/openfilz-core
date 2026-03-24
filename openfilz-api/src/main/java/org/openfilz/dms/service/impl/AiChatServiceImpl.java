package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.dto.request.AiChatRequest;
import org.openfilz.dms.dto.response.AiChatResponse;
import org.openfilz.dms.entity.AiChatConversation;
import org.openfilz.dms.entity.AiChatMessage;
import org.openfilz.dms.repository.AiChatConversationRepository;
import org.openfilz.dms.repository.AiChatMessageRepository;
import org.openfilz.dms.service.AiChatService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of AiChatService using Spring AI ChatClient with RAG.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.ai.active", havingValue = "true")
public class AiChatServiceImpl implements AiChatService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final AiProperties aiProperties;
    private final AiChatConversationRepository conversationRepository;
    private final AiChatMessageRepository messageRepository;

    @Override
    public Flux<AiChatResponse> chat(AiChatRequest request) {
        // 1. Resolve or create conversation
        Mono<UUID> conversationIdMono = request.getConversationId() != null
                ? Mono.just(request.getConversationId())
                : createConversation(request.getMessage());

        return conversationIdMono.flatMapMany(conversationId -> {
            // 2. Save user message
            Mono<Void> saveUserMsg = saveMessage(conversationId, "USER", request.getMessage());

            // 3. Retrieve relevant document chunks (RAG)
            Mono<String> contextMono = retrieveContext(request.getMessage());

            // 4. Load conversation history
            Mono<List<Message>> historyMono = loadConversationHistory(conversationId);

            return saveUserMsg
                    .then(Mono.zip(contextMono, historyMono))
                    .flatMapMany(tuple -> {
                        String ragContext = tuple.getT1();
                        List<Message> history = tuple.getT2();

                        // 5. Build the prompt with RAG context + history + user message
                        String augmentedMessage = buildAugmentedMessage(request.getMessage(), ragContext);

                        // 6. Stream the response
                        StringBuilder fullResponse = new StringBuilder();

                        return chatClient.prompt()
                                .messages(history)
                                .user(augmentedMessage)
                                .stream()
                                .content()
                                .map(chunk -> {
                                    fullResponse.append(chunk);
                                    return AiChatResponse.builder()
                                            .conversationId(conversationId)
                                            .content(chunk)
                                            .type(AiChatResponse.EventType.MESSAGE)
                                            .build();
                                })
                                .concatWith(Mono.defer(() ->
                                        // Save assistant response after streaming completes
                                        saveMessage(conversationId, "ASSISTANT", fullResponse.toString())
                                                .then(updateConversationTimestamp(conversationId))
                                                .thenReturn(AiChatResponse.builder()
                                                        .conversationId(conversationId)
                                                        .type(AiChatResponse.EventType.DONE)
                                                        .build())
                                ))
                                .onErrorResume(e -> {
                                    log.error("Error during AI chat streaming", e);
                                    return Flux.just(AiChatResponse.builder()
                                            .conversationId(conversationId)
                                            .content("An error occurred while processing your request: " + e.getMessage())
                                            .type(AiChatResponse.EventType.ERROR)
                                            .build());
                                });
                    });
        });
    }

    @Override
    public Flux<AiChatConversation> listConversations() {
        // For now, list all conversations. In production, filter by authenticated user.
        return conversationRepository.findAll()
                .sort((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt()));
    }

    @Override
    public Flux<AiChatResponse> getConversationHistory(UUID conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)
                .map(msg -> AiChatResponse.builder()
                        .conversationId(conversationId)
                        .content(msg.getContent())
                        .type(AiChatResponse.EventType.MESSAGE)
                        .build());
    }

    @Override
    public Mono<Void> deleteConversation(UUID conversationId) {
        return conversationRepository.deleteById(conversationId);
    }

    private Mono<UUID> createConversation(String firstMessage) {
        var conversation = AiChatConversation.builder()
                .title(firstMessage.length() > 100 ? firstMessage.substring(0, 100) + "..." : firstMessage)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        return conversationRepository.save(conversation).map(AiChatConversation::getId);
    }

    private Mono<Void> saveMessage(UUID conversationId, String role, String content) {
        var message = AiChatMessage.builder()
                .conversationId(conversationId)
                .role(role)
                .content(content)
                .createdAt(OffsetDateTime.now())
                .build();
        return messageRepository.save(message).then();
    }

    private Mono<Void> updateConversationTimestamp(UUID conversationId) {
        return conversationRepository.findById(conversationId)
                .flatMap(conv -> {
                    conv.setUpdatedAt(OffsetDateTime.now());
                    return conversationRepository.save(conv);
                })
                .then();
    }

    private Mono<List<Message>> loadConversationHistory(UUID conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)
                .map(msg -> (Message) switch (msg.getRole()) {
                    case "USER" -> new UserMessage(msg.getContent());
                    case "ASSISTANT" -> new AssistantMessage(msg.getContent());
                    case "SYSTEM" -> new SystemMessage(msg.getContent());
                    default -> new UserMessage(msg.getContent());
                })
                .collectList();
    }

    private Mono<String> retrieveContext(String query) {
        return Mono.fromCallable(() -> {
            var searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(aiProperties.getEmbedding().getTopK())
                    .similarityThreshold(aiProperties.getEmbedding().getSimilarityThreshold())
                    .build();

            List<Document> relevantDocs = vectorStore.similaritySearch(searchRequest);

            if (relevantDocs == null || relevantDocs.isEmpty()) {
                return "";
            }

            return relevantDocs.stream()
                    .map(doc -> {
                        String docName = doc.getMetadata().getOrDefault("document_name", "Unknown").toString();
                        return "[Document: " + docName + "]\n" + doc.getText();
                    })
                    .collect(Collectors.joining("\n\n---\n\n"));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String buildAugmentedMessage(String userMessage, String ragContext) {
        if (ragContext == null || ragContext.isBlank()) {
            return userMessage;
        }

        return """
                Here is relevant context from the user's documents:

                %s

                ---

                User question: %s
                """.formatted(ragContext, userMessage);
    }
}
