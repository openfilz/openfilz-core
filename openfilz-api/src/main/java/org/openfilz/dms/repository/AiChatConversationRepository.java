package org.openfilz.dms.repository;

import org.openfilz.dms.entity.AiChatConversation;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface AiChatConversationRepository extends ReactiveCrudRepository<AiChatConversation, UUID> {

    Flux<AiChatConversation> findByCreatedByOrderByUpdatedAtDesc(String createdBy);

    /**
     * Conversations visible to a user: their own, plus legacy rows created before
     * ownership stamping (created_by IS NULL), which stay visible to everyone.
     */
    @Query("SELECT * FROM ai_chat_conversations WHERE created_by = :userEmail OR created_by IS NULL ORDER BY updated_at DESC")
    Flux<AiChatConversation> findVisibleToUser(String userEmail);
}
