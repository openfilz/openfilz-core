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
import org.openfilz.dms.service.ai.AiAccessPolicy;
import org.openfilz.dms.service.ai.AiFailoverPolicy;
import org.openfilz.dms.service.ai.AiFallbackChain;
import org.openfilz.dms.service.ai.ChatClientAssembler;
import org.openfilz.dms.service.ai.DocumentAiTools;
import org.openfilz.dms.service.ai.DocumentAiToolsFactory;
import org.openfilz.dms.service.ai.UserChatClientResolver;
import org.openfilz.dms.service.ai.UserChatClientResolver.ResolvedChat;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Implementation of AiChatService using Spring AI ChatClient with RAG.
 * <p>
 * Per request, the chat model is resolved for the connected user (server default or BYOK
 * override), a fresh {@link DocumentAiTools} instance is created (its doc-link registry is
 * per-conversation-turn state — a singleton would cross-contaminate concurrent users), and
 * the {@link ChatClient} is assembled on top. Conversations are owned by their creator;
 * legacy rows without an owner stay visible to everyone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Lazy
public class AiChatServiceImpl implements AiChatService {

    private final UserChatClientResolver chatResolver;
    private final AiFallbackChain fallbackChain;
    private final ChatClientAssembler assembler;
    private final DocumentAiToolsFactory toolsFactory;
    private final VectorStore vectorStore;
    private final AiProperties aiProperties;
    private final AiChatConversationRepository conversationRepository;
    private final AiChatMessageRepository messageRepository;
    private final AiAccessPolicy accessPolicy;

    @Override
    public Flux<AiChatResponse> chat(AiChatRequest request, String userEmail) {
        log.debug("[AI] === New chat request (user={}) ===", userEmail);
        log.debug("[AI] User message: {}", request.getMessage());
        log.debug("[AI] Conversation ID: {}", request.getConversationId() != null ? request.getConversationId() : "(new conversation)");

        // 1. Resolve or create conversation (ownership-checked)
        Mono<UUID> conversationIdMono = request.getConversationId() != null
                ? requireVisible(request.getConversationId(), userEmail).map(AiChatConversation::getId)
                : createConversation(request.getMessage(), userEmail);

        // Capture the caller's Authentication: the tools re-establish it on every blocking
        // subscription so security-context-based enforcement applies inside tool calls.
        Mono<Optional<Authentication>> authenticationMono = ReactiveSecurityContextHolder.getContext()
                .map(ctx -> Optional.ofNullable(ctx.getAuthentication()))
                .defaultIfEmpty(Optional.empty());

        return Mono.zip(conversationIdMono, authenticationMono).flatMapMany(conversationAndAuth -> {
            UUID conversationId = conversationAndAuth.getT1();
            Authentication authentication = conversationAndAuth.getT2().orElse(null);
            log.debug("[AI] Conversation ID resolved: {}", conversationId);

            // 2. Resolve the user's chat model (server default or BYOK) and build the
            //    per-request tool instance + client on top of it.
            return chatResolver.resolve(userEmail).flatMapMany(resolved -> {
                // Models to try, best first: the user's own (or the server default) unless it is
                // cooling down after a quota failure, then the configured fallbacks.
                List<ResolvedChat> candidates = fallbackChain.candidates(resolved);
                ResolvedChat first = candidates.getFirst();
                DocumentAiTools tools = toolsFactory.create(first.chatModel(), userEmail, authentication);
                Set<String> ragDocumentNames = new HashSet<>();
                log.debug("[AI] Chat model: {} ({}){}", first.provider(), first.model(),
                        candidates.size() > 1 ? " (+" + (candidates.size() - 1) + " fallback)" : "");

                // 3. Save user message
                Mono<Void> saveUserMsg = saveMessage(conversationId, "USER", request.getMessage())
                        .doOnSuccess(v -> log.debug("[AI] User message saved to DB"));

                // 4. Retrieve relevant document chunks (RAG) — also registers found docs in the registry.
                //    Chunks are filtered to documents the requesting user can read.
                Mono<String> contextMono = retrieveContext(request.getMessage(), tools, ragDocumentNames, userEmail);

                // 5. Load conversation history
                Mono<List<Message>> historyMono = loadConversationHistory(conversationId);

                return saveUserMsg
                        .then(Mono.zip(contextMono, historyMono))
                        .flatMapMany(tuple -> {
                            String ragContext = tuple.getT1();
                            List<Message> history = tuple.getT2();

                            log.debug("[AI] RAG context: {}", ragContext.isBlank() ? "(none)" : ragContext.length() + " chars");
                            log.debug("[AI] Conversation history: {} previous messages", history.size());

                            // 6. Build the prompt with RAG context + history + user message
                            String augmentedMessage = buildAugmentedMessage(request.getMessage(), ragContext);
                            log.debug("[AI] Augmented prompt: {}", augmentedMessage.length() > 200
                                    ? augmentedMessage.substring(0, 200) + "..." : augmentedMessage);

                            // 7. Stream the response
                            StringBuilder fullResponse = new StringBuilder();

                            log.debug("[AI] Sending prompt to LLM (streaming)...");
                            return streamWithFailover(candidates, 0, resolved, tools, history, augmentedMessage, fullResponse)
                                    .doOnComplete(() -> log.debug("[AI] LLM streaming complete, raw response: {} chars", fullResponse.length()))
                                    .then(Mono.defer(() -> {
                                        // Post-process: enrich response with document links
                                        log.debug("[AI] Document registry: {} entries: {}", tools.getRegistry().size(), tools.getRegistry().keySet());
                                        String enriched = tools.enrichWithDocLinks(fullResponse.toString());

                                        // Append "Sources" section with links to documents found by RAG
                                        // This guarantees document links appear even when the LLM forgets to mention filenames
                                        enriched = appendSourceLinks(enriched, tools, ragDocumentNames);

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
                                                    // Folders whose content changed via tool calls — the frontend
                                                    // refreshes the file explorer only when it displays one of them
                                                    .modifiedFolderIds(tools.getModifiedFolders().isEmpty()
                                                            ? null : List.copyOf(tools.getModifiedFolders()))
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
        });
    }

    /**
     * Stream the answer from {@code candidates[index]}, falling over to the next candidate when
     * the model refuses for a reason another model could survive (quota exhausted, model retired,
     * provider down — see {@link AiFailoverPolicy}).
     * <p>
     * Two conditions must hold before a retry, and both are about not showing the user something
     * wrong:
     * <ul>
     *   <li><b>Nothing streamed yet.</b> Once tokens have reached the client, restarting on another
     *       model would splice two different answers together, so a mid-stream failure propagates.</li>
     *   <li><b>No tool has mutated anything.</b> Read-only tools are safe to repeat, but a retry
     *       after a move/rename/delete would run it twice. {@code modifiedFolders} is empty exactly
     *       when no mutating tool has fired this turn.</li>
     * </ul>
     * The failed model is benched in {@link AiFallbackChain} on the way out, so the requests that
     * follow skip it instead of each paying the same failing call.
     * <p>
     * A refused API key is handled separately from those retryable failures. When the provider
     * rejects the credentials of the model the user is actually on ({@code primary} — their BYOK
     * choice or the server default), the error surfaces: rerouting would hide a broken key an
     * operator has to fix. When it rejects a key that came from a fallback <em>pool</em>, the
     * whole key is disabled and the next candidate is tried instead — a pool of keys exists
     * precisely so one of them failing is survivable. It is logged at ERROR with the key's
     * fingerprint either way, so a bad pool key is loud rather than silent.
     */
    private Flux<String> streamWithFailover(List<ResolvedChat> candidates, int index, ResolvedChat primary,
                                            DocumentAiTools tools, List<Message> history,
                                            String augmentedMessage, StringBuilder fullResponse) {
        ResolvedChat candidate = candidates.get(index);
        tools.rebindChatModel(candidate.chatModel());
        ChatClient chatClient = assembler.assemble(candidate.chatModel(), tools);

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
                .onErrorResume(error -> {
                    AiFailoverPolicy.Failure failure = AiFailoverPolicy.classify(error);
                    fallbackChain.trip(candidate, failure);

                    // A pool key the provider refuses is retryable on the *next key*, though not
                    // on another model of the same key — so it gets its own path rather than
                    // Failure.shouldFailover().
                    boolean pooledKeyRefused = failure == AiFailoverPolicy.Failure.CREDENTIALS_REJECTED
                            && candidate != primary;
                    if (pooledKeyRefused && fallbackChain.disableKey(candidate)) {
                        log.error("[AI] {} rejected the fallback API key {} — dropping it from the pool "
                                        + "for the rest of this process; fix or remove it in "
                                        + "AI_FALLBACK_KEYS_{}",
                                candidate.provider(), candidate.keyRef(),
                                candidate.provider().toUpperCase(Locale.ROOT));
                    }

                    boolean mayRetry = failure.shouldFailover() || pooledKeyRefused;
                    // Skip past any candidate whose key was disabled — including by the line
                    // above — so a dead key costs one refused call, not one per model on it.
                    int nextIndex = nextUsable(candidates, index);
                    boolean hasNextCandidate = nextIndex >= 0;
                    boolean nothingStreamed = fullResponse.isEmpty();
                    boolean nothingMutated = tools.getModifiedFolders().isEmpty();

                    if (!mayRetry || !hasNextCandidate || !nothingStreamed || !nothingMutated) {
                        if (mayRetry && !hasNextCandidate) {
                            log.error("[AI] {} on {} ({}, key {}) and no fallback model left",
                                    failure, candidate.provider(), candidate.model(), candidate.keyRef());
                        } else if (mayRetry) {
                            log.error("[AI] {} on {} ({}, key {}) but cannot retry safely (streamed={}, mutated={})",
                                    failure, candidate.provider(), candidate.model(), candidate.keyRef(),
                                    !nothingStreamed, !nothingMutated);
                        }
                        return Flux.error(error);
                    }

                    ResolvedChat next = candidates.get(nextIndex);
                    log.warn("[AI] {} on {} ({}, key {}) — falling back to {} ({}, key {})", failure,
                            candidate.provider(), candidate.model(), candidate.keyRef(),
                            next.provider(), next.model(), next.keyRef());
                    return streamWithFailover(candidates, nextIndex, primary, tools, history,
                            augmentedMessage, fullResponse);
                });
    }

    /** Index of the next candidate still worth an attempt, or -1 when the chain is spent. */
    private int nextUsable(List<ResolvedChat> candidates, int index) {
        for (int i = index + 1; i < candidates.size(); i++) {
            if (fallbackChain.isUsable(candidates.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public Flux<AiChatConversation> listConversations(String userEmail) {
        log.debug("[AI] Listing conversations for user {}", userEmail);
        return conversationRepository.findVisibleToUser(userEmail);
    }

    @Override
    public Flux<AiChatResponse> getConversationHistory(UUID conversationId, String userEmail) {
        log.debug("[AI] Loading conversation history: {} (user={})", conversationId, userEmail);
        return requireVisible(conversationId, userEmail)
                .flatMapMany(conversation -> messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId))
                .map(msg -> AiChatResponse.builder()
                        .conversationId(conversationId)
                        .content(msg.getContent())
                        .type(AiChatResponse.EventType.MESSAGE)
                        .build());
    }

    @Override
    public Mono<Void> deleteConversation(UUID conversationId, String userEmail) {
        log.debug("[AI] Deleting conversation: {} (user={})", conversationId, userEmail);
        return requireVisible(conversationId, userEmail)
                .flatMap(conversation -> conversationRepository.deleteById(conversationId));
    }

    /**
     * Load a conversation the user is allowed to see: their own, or a legacy unowned one.
     * Someone else's conversation surfaces as 404 (not 403) to avoid leaking its existence.
     */
    private Mono<AiChatConversation> requireVisible(UUID conversationId, String userEmail) {
        return conversationRepository.findById(conversationId)
                .filter(conversation -> conversation.getCreatedBy() == null
                        || conversation.getCreatedBy().equals(userEmail))
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found")));
    }

    private Mono<UUID> createConversation(String firstMessage, String userEmail) {
        log.debug("[AI] Creating new conversation for {}, title: {}", userEmail, firstMessage.length() > 50
                ? firstMessage.substring(0, 50) + "..." : firstMessage);
        var conversation = AiChatConversation.builder()
                .title(firstMessage.length() > 100 ? firstMessage.substring(0, 100) + "..." : firstMessage)
                .createdBy(userEmail)
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
        // Assistant messages are stored enriched with [[doc:...]] markers for the frontend;
        // strip them before prompting so the LLM never sees (and never mimics) the marker syntax.
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)
                .map(msg -> (Message) switch (msg.getRole()) {
                    case "USER" -> new UserMessage(msg.getContent());
                    case "ASSISTANT" -> new AssistantMessage(DocumentAiTools.stripDocMarkers(msg.getContent()));
                    case "SYSTEM" -> new SystemMessage(msg.getContent());
                    default -> new UserMessage(msg.getContent());
                })
                .collectList()
                .doOnNext(msgs -> log.debug("[AI] Loaded {} history messages", msgs.size()));
    }

    /** Maximum total characters of RAG context to inject into the prompt (avoids overwhelming the LLM). */
    private static final int MAX_RAG_CONTEXT_CHARS = 4000;

    private Mono<String> retrieveContext(String query, DocumentAiTools tools, Set<String> ragDocumentNames, String userEmail) {
        log.debug("[AI] RAG: searching vector store for: '{}' (topK={}, threshold={})",
                query, aiProperties.getEmbedding().getTopK(), aiProperties.getEmbedding().getSimilarityThreshold());
        return Mono.fromCallable(() -> {
            var searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(aiProperties.getEmbedding().getTopK())
                    .similarityThreshold(aiProperties.getEmbedding().getSimilarityThreshold())
                    .build();

            List<Document> relevantDocs = vectorStore.similaritySearch(searchRequest);

            // The vector store is shared across all users — never let another user's content
            // reach this user's prompt. Keep only chunks of documents the user can read;
            // under a per-document policy, chunks without a document_id are dropped too.
            if (relevantDocs != null && !accessPolicy.permitAll()) {
                relevantDocs = relevantDocs.stream()
                        .filter(doc -> isChunkReadable(doc, userEmail))
                        .toList();
            }

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
            var includedDocs = new HashSet<String>();

            for (var doc : relevantDocs) {
                String docName = doc.getMetadata().getOrDefault("document_name", "Unknown").toString();
                String text = doc.getText();

                // Skip chunks with very low text content (likely failed Tika extraction)
                if (text == null || text.length() < 20) {
                    log.debug("[AI] RAG: skipping chunk from '{}' — too short ({} chars)", docName, text != null ? text.length() : 0);
                    // Still register the document for linking even if text is short
                    registerRagDocument(doc, tools, ragDocumentNames);
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
                        registerRagDocument(doc, tools, ragDocumentNames);
                        includedDocs.add(docName);
                    }
                    log.debug("[AI] RAG: capped context at {} chars (limit={})", context.length(), MAX_RAG_CONTEXT_CHARS);
                    break;
                }
                if (!context.isEmpty()) context.append("\n\n---\n\n");
                context.append(chunk);
                registerRagDocument(doc, tools, ragDocumentNames);
                includedDocs.add(docName);
            }

            log.debug("[AI] RAG: included documents: {}, total context: {} chars", includedDocs, context.length());
            return context.toString();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * True when the requesting user may read the document a RAG chunk belongs to.
     * Chunks with no {@code document_id} metadata cannot be attributed to a document,
     * so under a per-document policy they are treated as NOT readable (fail closed).
     */
    private boolean isChunkReadable(Document doc, String userEmail) {
        String docId = doc.getMetadata().getOrDefault("document_id", "").toString();
        if (docId.isBlank()) {
            log.debug("[AI] RAG: dropping chunk without document_id metadata (per-document access policy active)");
            return false;
        }
        try {
            boolean readable = Boolean.TRUE.equals(accessPolicy.canRead(UUID.fromString(docId), userEmail).block());
            if (!readable) {
                log.debug("[AI] RAG: dropping chunk of document {} — not readable by {}", docId, userEmail);
            }
            return readable;
        } catch (IllegalArgumentException e) {
            log.debug("[AI] RAG: dropping chunk with invalid document_id '{}'", docId);
            return false;
        }
    }

    /** Register a RAG-discovered document in the tool registry for doc-link enrichment (but not for Sources). */
    private void registerRagDocument(Document doc, DocumentAiTools tools, Set<String> ragDocumentNames) {
        String docName = doc.getMetadata().getOrDefault("document_name", "Unknown").toString();
        ragDocumentNames.add(docName);
        String docId = doc.getMetadata().getOrDefault("document_id", "").toString();
        String parentId = doc.getMetadata().getOrDefault("parent_id", "").toString();
        if (!docId.isBlank()) {
            try {
                tools.getRegistry().putIfAbsent(docName,
                        new DocumentAiTools.DocRef(
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
    private String appendSourceLinks(String response, DocumentAiTools tools, Set<String> ragDocumentNames) {
        var registry = tools.getRegistry();
        if (registry.isEmpty()) return response;

        // Find documents that are NOT already linked AND not from RAG context only
        var unlinked = registry.values().stream()
                .filter(ref -> !response.contains("[[doc:" + ref.id()))
                .filter(ref -> !ragDocumentNames.contains(ref.name()))
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
