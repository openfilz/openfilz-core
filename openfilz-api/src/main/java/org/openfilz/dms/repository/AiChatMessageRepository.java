package org.openfilz.dms.repository;

import org.openfilz.dms.entity.AiChatMessage;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface AiChatMessageRepository extends ReactiveCrudRepository<AiChatMessage, UUID> {

    Flux<AiChatMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);
}
