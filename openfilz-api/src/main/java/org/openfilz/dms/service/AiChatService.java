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
 */
public interface AiChatService {

    /**
     * Send a message and get a streaming response from the AI assistant.
     * Uses RAG (Retrieval-Augmented Generation) to provide context from documents.
     *
     * @param request the chat request with user message and optional conversation ID
     * @return a Flux of response chunks for SSE streaming
     */
    Flux<AiChatResponse> chat(AiChatRequest request);

    /**
     * List all conversations for the current user.
     *
     * @return a Flux of conversations
     */
    Flux<AiChatConversation> listConversations();

    /**
     * Get conversation history (all messages) for a given conversation.
     *
     * @param conversationId the conversation UUID
     * @return a Flux of chat responses representing the conversation history
     */
    Flux<AiChatResponse> getConversationHistory(UUID conversationId);

    /**
     * Delete a conversation and all its messages.
     *
     * @param conversationId the conversation UUID
     * @return empty Mono on completion
     */
    Mono<Void> deleteConversation(UUID conversationId);
}
