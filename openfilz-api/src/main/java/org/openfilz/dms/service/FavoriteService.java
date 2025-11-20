package org.openfilz.dms.service;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Service for managing user favorites
 */
public interface FavoriteService {

    /**
     * Add a document to user's favorites
     *
     * @param documentId Document ID to favorite
     * @param userId User identifier
     * @return Void when added
     */
    Mono<Void> addFavorite(UUID documentId, String userId);

    /**
     * Remove a document from user's favorites
     *
     * @param documentId Document ID to unfavorite
     * @param userId User identifier
     * @return Void when removed
     */
    Mono<Void> removeFavorite(UUID documentId, String userId);

    /**
     * Toggle favorite status for a document
     *
     * @param documentId Document ID
     * @param userId User identifier
     * @return true if now favorited, false if unfavorited
     */
    Mono<Boolean> toggleFavorite(UUID documentId, String userId);

    /**
     * Check if a document is favorited by user
     *
     * @param documentId Document ID
     * @param userId User identifier
     * @return true if favorited
     */
    Mono<Boolean> isFavorite(UUID documentId, String userId);

}
