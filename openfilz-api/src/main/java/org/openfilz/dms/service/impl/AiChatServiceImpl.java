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
import org.openfilz.dms.service.ai.DocumentAiTools;
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
    private final DocumentAiTools documentAiTools;

    @Override
    public Flux<AiChatResponse> chat(AiChatRequest request) {
        log.debug("[AI] === New chat request ===");
        log.debug("[AI] User message: {}", request.getMessage());
        log.debug("[AI] Conversation ID: {}", request.getConversationId() != null ? request.getConversationId() : "(new conversation)");

        // 1. Resolve or create conversation
        Mono<UUID> conversationIdMono = request.getConversationId() != null
                ? Mono.just(request.getConversationId())
                : createConversation(request.getMessage());

        return conversationIdMono.flatMapMany(conversationId -> {
            log.debug("[AI] Conversation ID resolved: {}", conversationId);

            // 2. Save user message and clear document registry for this turn
            documentAiTools.clearRegistry();
            Mono<Void> saveUserMsg = saveMessage(conversationId, "USER", request.getMessage())
                    .doOnSuccess(v -> log.debug("[AI] User message saved to DB"));

            // 3. Retrieve relevant document chunks (RAG) — also registers found docs in the registry
            Mono<String> contextMono = retrieveContext(request.getMessage());

            // 4. Load conversation history
            Mono<List<Message>> historyMono = loadConversationHistory(conversationId);

            return saveUserMsg
                    .then(Mono.zip(contextMono, historyMono))
                    .flatMapMany(tuple -> {
                        String ragContext = tuple.getT1();
                        List<Message> history = tuple.getT2();

                        log.debug("[AI] RAG context: {}", ragContext.isBlank() ? "(none)" : ragContext.length() + " chars");
                        log.debug("[AI] Conversation history: {} previous messages", history.size());

                        // 5. Build the prompt with RAG context + history + user message
                        String augmentedMessage = buildAugmentedMessage(request.getMessage(), ragContext);
                        log.debug("[AI] Augmented prompt: {}", augmentedMessage.length() > 200
                                ? augmentedMessage.substring(0, 200) + "..." : augmentedMessage);

                        // 6. Stream the response (registry already cleared + populated by RAG above)
                        StringBuilder fullResponse = new StringBuilder();

                        log.debug("[AI] Sending prompt to LLM (streaming)...");
                        return chatClient.prompt()
                                .messages(history)
                                .user(augmentedMessage)
                                .stream()
                                .content()
                                .doOnNext(chunk -> {
                                    fullResponse.append(chunk);
                                    if (fullResponse.length() == chunk.length()) {
                                        log.debug("[AI] LLM started streaming response");
                                    }
                                })
                                .doOnComplete(() -> log.debug("[AI] LLM streaming complete, raw response: {} chars", fullResponse.length()))
                                .then(Mono.defer(() -> {
                                    // Post-process: enrich response with document links
                                    log.debug("[AI] Document registry: {} entries: {}", documentAiTools.getRegistry().size(), documentAiTools.getRegistry().keySet());
                                    String enriched = documentAiTools.enrichWithDocLinks(fullResponse.toString());

                                    // Append "Sources" section with links to documents found by RAG
                                    // This guarantees document links appear even when the LLM forgets to mention filenames
                                    enriched = appendSourceLinks(enriched);

                                    log.debug("[AI] Enriched response: {} chars (was {} chars)", enriched.length(), fullResponse.length());
                                    log.debug("[AI] Final response preview: {}", enriched.length() > 300
                                            ? enriched.substring(0, 300) + "..." : enriched);

                                    return saveMessage(conversationId, "ASSISTANT", enriched)
                                            .doOnSuccess(v -> log.debug("[AI] Assistant message saved to DB"))
                                            .then(updateConversationTimestamp(conversationId))
                                            .thenReturn(enriched);
                                }))
                                .flatMapMany(enriched -> Flux.just(
                                        AiChatResponse.builder()
                                                .conversationId(conversationId)
                                                .content(enriched)
                                                .type(AiChatResponse.EventType.MESSAGE)
                                                .build(),
                                        AiChatResponse.builder()
                                                .conversationId(conversationId)
                                                .type(AiChatResponse.EventType.DONE)
                                                .build()
                                ))
                                .doOnComplete(() -> log.debug("[AI] === Chat request complete ==="))
                                .onErrorResume(e -> {
                                    log.error("[AI] Error during AI chat streaming", e);
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
        log.debug("[AI] Listing conversations");
        return conversationRepository.findAll()
                .sort((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt()));
    }

    @Override
    public Flux<AiChatResponse> getConversationHistory(UUID conversationId) {
        log.debug("[AI] Loading conversation history: {}", conversationId);
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)
                .map(msg -> AiChatResponse.builder()
                        .conversationId(conversationId)
                        .content(msg.getContent())
                        .type(AiChatResponse.EventType.MESSAGE)
                        .build());
    }

    @Override
    public Mono<Void> deleteConversation(UUID conversationId) {
        log.debug("[AI] Deleting conversation: {}", conversationId);
        return conversationRepository.deleteById(conversationId);
    }

    private Mono<UUID> createConversation(String firstMessage) {
        log.debug("[AI] Creating new conversation, title: {}", firstMessage.length() > 50
                ? firstMessage.substring(0, 50) + "..." : firstMessage);
        var conversation = AiChatConversation.builder()
                .title(firstMessage.length() > 100 ? firstMessage.substring(0, 100) + "..." : firstMessage)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        return conversationRepository.save(conversation)
                .doOnNext(c -> log.debug("[AI] Conversation created: {}", c.getId()))
                .map(AiChatConversation::getId);
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
                .collectList()
                .doOnNext(msgs -> log.debug("[AI] Loaded {} history messages", msgs.size()));
    }

    /** Maximum total characters of RAG context to inject into the prompt (avoids overwhelming the LLM). */
    private static final int MAX_RAG_CONTEXT_CHARS = 4000;

    private Mono<String> retrieveContext(String query) {
        log.debug("[AI] RAG: searching vector store for: '{}' (topK={}, threshold={})",
                query, aiProperties.getEmbedding().getTopK(), aiProperties.getEmbedding().getSimilarityThreshold());
        return Mono.fromCallable(() -> {
            var searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(aiProperties.getEmbedding().getTopK())
                    .similarityThreshold(aiProperties.getEmbedding().getSimilarityThreshold())
                    .build();

            List<Document> relevantDocs = vectorStore.similaritySearch(searchRequest);

            if (relevantDocs == null || relevantDocs.isEmpty()) {
                log.debug("[AI] RAG: no relevant documents found (threshold may be too high, or vector store may be empty)");
                return "";
            }

            log.debug("[AI] RAG: found {} relevant chunks", relevantDocs.size());
            relevantDocs.forEach(doc -> {
                String docName = doc.getMetadata().getOrDefault("document_name", "Unknown").toString();
                log.debug("[AI] RAG chunk: document='{}', score={}, text={}chars",
                        docName, doc.getScore(), doc.getText() != null ? doc.getText().length() : 0);
            });

            // Build context from the best chunks, capping total size
            double bestScore = relevantDocs.getFirst().getScore();
            StringBuilder context = new StringBuilder();
            var includedDocs = new java.util.HashSet<String>();

            for (var doc : relevantDocs) {
                String docName = doc.getMetadata().getOrDefault("document_name", "Unknown").toString();
                String text = doc.getText();

                // Skip chunks with very low text content (likely failed Tika extraction)
                if (text == null || text.length() < 20) {
                    log.debug("[AI] RAG: skipping chunk from '{}' — too short ({} chars)", docName, text != null ? text.length() : 0);
                    // Still register the document for linking even if text is short
                    registerRagDocument(doc);
                    includedDocs.add(docName);
                    continue;
                }

                // Skip documents whose score is much lower than the best (likely irrelevant)
                if (doc.getScore() < bestScore * 0.85) {
                    log.debug("[AI] RAG: skipping chunk from '{}' — score {} too far from best {}", docName, doc.getScore(), bestScore);
                    break;
                }

                String chunk = "[Document: " + docName + "]\n" + text;
                if (context.length() + chunk.length() > MAX_RAG_CONTEXT_CHARS) {
                    if (context.isEmpty()) {
                        context.append(chunk, 0, Math.min(chunk.length(), MAX_RAG_CONTEXT_CHARS));
                        registerRagDocument(doc);
                        includedDocs.add(docName);
                    }
                    log.debug("[AI] RAG: capped context at {} chars (limit={})", context.length(), MAX_RAG_CONTEXT_CHARS);
                    break;
                }
                if (!context.isEmpty()) context.append("\n\n---\n\n");
                context.append(chunk);
                registerRagDocument(doc);
                includedDocs.add(docName);
            }

            log.debug("[AI] RAG: included documents: {}, total context: {} chars", includedDocs, context.length());
            return context.toString();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Register a RAG-discovered document in the tool registry for doc-link enrichment. */
    private void registerRagDocument(Document doc) {
        String docName = doc.getMetadata().getOrDefault("document_name", "Unknown").toString();
        String docId = doc.getMetadata().getOrDefault("document_id", "").toString();
        String parentId = doc.getMetadata().getOrDefault("parent_id", "").toString();
        if (!docId.isBlank()) {
            try {
                documentAiTools.getRegistry().putIfAbsent(docName,
                        new org.openfilz.dms.service.ai.DocumentAiTools.DocRef(
                                UUID.fromString(docId),
                                parentId.isBlank() ? null : UUID.fromString(parentId),
                                "FILE", docName));
            } catch (Exception e) {
                log.debug("[AI] RAG: failed to register document '{}': {}", docName, e.getMessage());
            }
        }
    }

    /**
     * Appends a "Sources" section with [[doc:...]] links for all documents in the registry
     * that aren't already linked in the response text. This guarantees the user always sees
     * clickable links to relevant documents, even when the LLM doesn't mention the filename.
     */
    private String appendSourceLinks(String response) {
        var registry = documentAiTools.getRegistry();
        if (registry.isEmpty()) return response;

        // Find documents that are NOT already linked (no [[doc:...]] marker for them)
        var unlinked = registry.values().stream()
                .filter(ref -> !response.contains("[[doc:" + ref.id()))
                .toList();

        if (unlinked.isEmpty()) return response;

        StringBuilder sources = new StringBuilder(response);
        sources.append("\n\n**Sources:**\n");
        for (var ref : unlinked) {
            sources.append("- [[doc:%s:%s:%s:%s]]\n".formatted(
                    ref.id(),
                    ref.parentId() != null ? ref.parentId() : "root",
                    ref.type(),
                    ref.name()));
        }
        return sources.toString();
    }

    private String buildAugmentedMessage(String userMessage, String ragContext) {
        if (ragContext == null || ragContext.isBlank()) {
            return userMessage;
        }

        return """
                %s

                Note: I also found some potentially related content from the document library below. \
                Only use this if it is directly relevant to the user's question. \
                If the user is asking to find, list, or read a specific file or folder, \
                use the tools (searchByName, listFolder, readDocumentContent) instead of this context. \
                Always mention the document name when referencing information from it.

                ---
                %s
                """.formatted(userMessage, ragContext);
    }
}
