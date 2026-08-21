package org.openfilz.dms.service;

import org.openfilz.dms.dto.request.AiChatRequest;
import org.openfilz.dms.dto.response.AiChatResponse;
import org.openfilz.dms.entity.AiChatConversation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Service for AI-powered document chat.
 * Handles conversation management, RAG retrieval, and LLM interaction.
 * All operations are scoped to the connected user: conversations are owned by their
 * creator (legacy rows without an owner stay visible to everyone), and the chat model
 * is resolved per user (server default, or the user's BYOK override).
 */
public interface AiChatService {

    /**
     * Send a message and get a streaming response from the AI assistant.
     * Uses RAG (Retrieval-Augmented Generation) to provide context from documents.
     *
     * @param request   the chat request with user message and optional conversation ID
     * @param userEmail the connected user (conversation owner + BYOK model resolution)
     * @return a Flux of response chunks for SSE streaming
     */
    Flux<AiChatResponse> chat(AiChatRequest request, String userEmail);

    /**
     * List the conversations visible to this user (own + legacy unowned).
     *
     * @param userEmail the connected user
     * @return a Flux of conversations
     */
    Flux<AiChatConversation> listConversations(String userEmail);

    /**
     * Get conversation history (all messages) for a given conversation.
     *
     * @param conversationId the conversation UUID
     * @param userEmail      the connected user (must own the conversation, or it must be unowned)
     * @return a Flux of chat responses representing the conversation history
     */
    Flux<AiChatResponse> getConversationHistory(UUID conversationId, String userEmail);

    /**
     * Delete a conversation and all its messages.
     *
     * @param conversationId the conversation UUID
     * @param userEmail      the connected user (must own the conversation, or it must be unowned)
     * @return empty Mono on completion
     */
    Mono<Void> deleteConversation(UUID conversationId, String userEmail);
}
