package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.AiChatRequest;
import org.openfilz.dms.dto.response.AiChatResponse;
import org.openfilz.dms.entity.AiChatConversation;
import org.openfilz.dms.service.AiChatService;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * REST controller for AI document chat.
 * Provides SSE streaming for chat responses and conversation management endpoints.
 * All operations are scoped to the connected user (conversation ownership + BYOK model).
 * <p>
 * The controller is always mapped and gates every endpoint on {@code openfilz.ai.active}
 * <em>at runtime</em> (404 when off): bean conditions are evaluated at build time in GraalVM
 * native images, so the feature toggle must never be a bean condition. The service is
 * injected {@code @Lazy} so a disabled deployment never initializes the AI pipeline.
 */
@Slf4j
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_AI)
@SecurityRequirement(name = "keycloak_auth")
@Tag(name = "AI Chat", description = "AI-powered document chat with RAG and function calling")
public class AiChatController implements UserInfoService {

    private final AiChatService aiChatService;
    private final AiProperties aiProperties;

    public AiChatController(@Lazy AiChatService aiChatService, AiProperties aiProperties) {
        this.aiChatService = aiChatService;
        this.aiProperties = aiProperties;
    }

    /** 404 when the AI feature is disabled at runtime — same shape as when it wasn't deployed. */
    private void requireAiActive() {
        if (!aiProperties.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI feature is disabled");
        }
    }

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
        requireAiActive();
        log.info("AI chat request: conversationId={}", request.getConversationId());
        return getConnectedUserEmail()
                .flatMapMany(userEmail -> aiChatService.chat(request, userEmail));
    }

    /**
     * List all conversations for the current user.
     */
    @GetMapping(value = "/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List conversations", description = "Get all AI chat conversations for the current user")
    public Flux<AiChatConversation> listConversations() {
        requireAiActive();
        return getConnectedUserEmail()
                .flatMapMany(aiChatService::listConversations);
    }

    /**
     * Get the message history of a conversation.
     */
    @GetMapping(value = "/conversations/{conversationId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get conversation history", description = "Get all messages in a conversation")
    public Flux<AiChatResponse> getConversationHistory(@PathVariable UUID conversationId) {
        requireAiActive();
        return getConnectedUserEmail()
                .flatMapMany(userEmail -> aiChatService.getConversationHistory(conversationId, userEmail));
    }

    /**
     * Delete a conversation and all its messages.
     */
    @DeleteMapping("/conversations/{conversationId}")
    @Operation(summary = "Delete conversation", description = "Delete a conversation and all its messages")
    public Mono<Void> deleteConversation(@PathVariable UUID conversationId) {
        requireAiActive();
        log.info("Deleting AI conversation: {}", conversationId);
        return getConnectedUserEmail()
                .flatMap(userEmail -> aiChatService.deleteConversation(conversationId, userEmail));
    }
}
