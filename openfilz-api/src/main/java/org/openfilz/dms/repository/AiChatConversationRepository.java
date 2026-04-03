package org.openfilz.dms.repository;

import org.openfilz.dms.entity.AiChatConversation;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface AiChatConversationRepository extends ReactiveCrudRepository<AiChatConversation, UUID> {

    Flux<AiChatConversation> findByCreatedByOrderByUpdatedAtDesc(String createdBy);
}
