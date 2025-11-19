package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.service.FavoriteService;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * REST controller for managing user favorites
 */
@Slf4j
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FAVORITES)
@RequiredArgsConstructor
@Tag(name = "Favorites", description = "User favorites management")
public class FavoriteController implements UserInfoService {

    private final FavoriteService favoriteService;

    /**
     * Add a document to favorites
     *
     * @param documentId Document ID to favorite
     * @return Void when added
     */
    @PostMapping(value = "/{documentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Add document to favorites",
            description = "Add a file or folder to the user's favorites"
    )
    public Mono<Void> addFavorite(@PathVariable UUID documentId) {
        return getConnectedUserEmail()
                .doOnNext(userId -> log.info("User {} adding document {} to favorites", userId, documentId))
                .flatMap(userId -> favoriteService.addFavorite(documentId, userId));
    }

    /**
     * Remove a document from favorites
     *
     * @param documentId Document ID to unfavorite
     * @return Void when removed
     */
    @DeleteMapping(value = "/{documentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Remove document from favorites",
            description = "Remove a file or folder from the user's favorites"
    )
    public Mono<Void> removeFavorite(@PathVariable UUID documentId) {
        return getConnectedUserEmail()
                .doOnNext(userId -> log.info("User {} removing document {} from favorites", userId, documentId))
                .flatMap(userId -> favoriteService.removeFavorite(documentId, userId));
    }

    /**
     * Toggle favorite status
     *
     * @param documentId Document ID
     * @return true if now favorited, false if unfavorited
     */
    @PutMapping(value = "/{documentId}/toggle", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Toggle favorite status",
            description = "Toggle favorite status for a document (add if not favorited, remove if already favorited)"
    )
    public Mono<Boolean> toggleFavorite(@PathVariable UUID documentId) {
        return getConnectedUserEmail()
                .doOnNext(userId -> log.info("User {} toggling favorite status for document {}", userId, documentId))
                .flatMap(userId -> favoriteService.toggleFavorite(documentId, userId));
    }

    /**
     * Check if a document is favorited
     *
     * @param documentId Document ID
     * @return true if favorited
     */
    @GetMapping(value = "/{documentId}/is-favorite", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Check favorite status",
            description = "Check if a document is in the user's favorites"
    )
    public Mono<Boolean> isFavorite(@PathVariable UUID documentId) {
        return getConnectedUserEmail()
                .flatMap(userId -> favoriteService.isFavorite(documentId, userId));
    }

}
