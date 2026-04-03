package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.AiChatRequest;
import org.openfilz.dms.dto.response.AiChatResponse;
import org.openfilz.dms.entity.AiChatConversation;
import org.openfilz.dms.service.AiChatService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * REST controller for AI document chat.
 * Provides SSE streaming for chat responses and conversation management endpoints.
 */
@Slf4j
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_AI)
@RequiredArgsConstructor
@SecurityRequirement(name = "keycloak_auth")
@Tag(name = "AI Chat", description = "AI-powered document chat with RAG and function calling")
@ConditionalOnProperty(name = "openfilz.ai.active", havingValue = "true")
public class AiChatController {

    private final AiChatService aiChatService;

    /**
     * Send a message to the AI assistant and receive a streaming response via SSE.
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "Chat with AI assistant",
            description = "Send a message and receive a streaming response. " +
                    "The AI can answer questions about documents, search, summarize, and reorganize files."
    )
    public Flux<AiChatResponse> chat(@Valid @RequestBody AiChatRequest request) {
        log.info("AI chat request: conversationId={}", request.getConversationId());
        return aiChatService.chat(request);
    }

    /**
     * List all conversations for the current user.
     */
    @GetMapping(value = "/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List conversations", description = "Get all AI chat conversations for the current user")
    public Flux<AiChatConversation> listConversations() {
        return aiChatService.listConversations();
    }

    /**
     * Get the message history of a conversation.
     */
    @GetMapping(value = "/conversations/{conversationId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get conversation history", description = "Get all messages in a conversation")
    public Flux<AiChatResponse> getConversationHistory(@PathVariable UUID conversationId) {
        return aiChatService.getConversationHistory(conversationId);
    }

    /**
     * Delete a conversation and all its messages.
     */
    @DeleteMapping("/conversations/{conversationId}")
    @Operation(summary = "Delete conversation", description = "Delete a conversation and all its messages")
    public Mono<Void> deleteConversation(@PathVariable UUID conversationId) {
        log.info("Deleting AI conversation: {}", conversationId);
        return aiChatService.deleteConversation(conversationId);
    }
}
