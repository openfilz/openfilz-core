package org.openfilz.dms.repository;

import org.openfilz.dms.entity.UserFavorite;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for managing user favorites
 */
public interface UserFavoriteRepository extends ReactiveCrudRepository<UserFavorite, Long> {

    /**
     * Check if a document is favorited by a user
     *
     * @param userId User identifier
     * @param documentId Document ID
     * @return true if favorited, false otherwise
     */
    Mono<Boolean> existsByUserIdAndDocumentId(String userId, UUID documentId);

    /**
     * Find a favorite by user and document
     *
     * @param userId User identifier
     * @param documentId Document ID
     * @return UserFavorite if exists
     */
    Mono<UserFavorite> findByUserIdAndDocumentId(String userId, UUID documentId);

    /**
     * Find all favorites for a user
     *
     * @param userId User identifier
     * @return Flux of user favorites
     */
    Flux<UserFavorite> findByUserId(String userId);

    /**
     * Delete a favorite by user and document
     *
     * @param userId User identifier
     * @param documentId Document ID
     * @return Void when deleted
     */
    Mono<Void> deleteByUserIdAndDocumentId(String userId, UUID documentId);
}
