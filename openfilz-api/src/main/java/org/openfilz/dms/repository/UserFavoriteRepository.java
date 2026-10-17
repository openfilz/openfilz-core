package org.openfilz.dms.repository;

import org.openfilz.dms.entity.UserFavorite;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

/**
 * Repository for managing user favorites
 */
public interface UserFavoriteRepository extends ReactiveCrudRepository<UserFavorite, Long> {

    /**
     * Check if a document is favorited by a user
     *
     * @param email User email
     * @param docId Document ID
     * @return true if favorited, false otherwise
     */
    Mono<Boolean> existsByEmailAndDocId(String email, UUID docId);

     /**
     * Delete a favorite by user and document
     *
     * @param email User identifier
     * @param docId Document ID
     * @return Void when deleted
     */
    Mono<Void> deleteByEmailAndDocId(String email, UUID docId);

    /**
     * The documents among {@code docIds} that the user has in their favorites (one query for a page of hits)
     *
     * @param email  User email
     * @param docIds Document IDs to check (must not be empty)
     * @return the favorited document IDs
     */
    @Query("SELECT doc_id FROM user_favorites WHERE email = :email AND doc_id IN (:docIds)")
    Flux<UUID> findFavoriteDocIds(String email, Collection<UUID> docIds);
}
