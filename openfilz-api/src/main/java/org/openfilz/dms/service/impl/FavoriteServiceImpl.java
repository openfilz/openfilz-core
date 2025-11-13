package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.entity.UserFavorite;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.repository.UserFavoriteRepository;
import org.openfilz.dms.service.FavoriteService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Implementation of FavoriteService
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FavoriteServiceImpl implements FavoriteService {

    private final UserFavoriteRepository userFavoriteRepository;
    private final DocumentRepository documentRepository;

    @Override
    public Mono<Void> addFavorite(UUID documentId, String userId) {
        log.debug("Adding document {} to favorites for user {}", documentId, userId);

        // Check if document exists first
        return documentRepository.existsById(documentId)
                .flatMap(exists -> {
                    if (!exists) {
                        return Mono.error(new IllegalArgumentException("Document not found: " + documentId));
                    }

                    // Check if already favorited
                    return userFavoriteRepository.existsByUserIdAndDocumentId(userId, documentId)
                            .flatMap(alreadyFavorited -> {
                                if (alreadyFavorited) {
                                    log.debug("Document {} already favorited by user {}", documentId, userId);
                                    return Mono.empty();
                                }

                                // Create new favorite
                                UserFavorite favorite = UserFavorite.builder()
                                        .userId(userId)
                                        .documentId(documentId)
                                        .createdAt(OffsetDateTime.now())
                                        .build();

                                return userFavoriteRepository.save(favorite).then();
                            });
                });
    }

    @Override
    public Mono<Void> removeFavorite(UUID documentId, String userId) {
        log.debug("Removing document {} from favorites for user {}", documentId, userId);
        return userFavoriteRepository.deleteByUserIdAndDocumentId(userId, documentId);
    }

    @Override
    public Mono<Boolean> toggleFavorite(UUID documentId, String userId) {
        log.debug("Toggling favorite status for document {} for user {}", documentId, userId);

        return userFavoriteRepository.existsByUserIdAndDocumentId(userId, documentId)
                .flatMap(isFavorited -> {
                    if (isFavorited) {
                        // Remove from favorites
                        return removeFavorite(documentId, userId).thenReturn(false);
                    } else {
                        // Add to favorites
                        return addFavorite(documentId, userId).thenReturn(true);
                    }
                });
    }

    @Override
    public Mono<Boolean> isFavorite(UUID documentId, String userId) {
        return userFavoriteRepository.existsByUserIdAndDocumentId(userId, documentId);
    }

    @Override
    public Flux<FolderElementInfo> listFavorites(String userId) {
        log.debug("Listing all favorites for user {}", userId);

        return userFavoriteRepository.findByUserId(userId)
                .flatMap(favorite -> documentRepository.findById(favorite.getDocumentId())
                        .map(this::mapToFolderElementInfo)
                        .doOnNext(info -> log.debug("Found favorite: {}", info.name()))
                );
    }

    private FolderElementInfo mapToFolderElementInfo(Document document) {
        String metadataString = document.getMetadata() != null
                ? document.getMetadata().asString()
                : null;

        return new FolderElementInfo(
                document.getId(),
                document.getType(),
                document.getContentType(),
                document.getName(),
                metadataString,
                document.getSize(),
                document.getCreatedAt(),
                document.getUpdatedAt(),
                document.getCreatedBy(),
                document.getUpdatedBy(),
                true // isFavorite is always true for items in favorites list
        );
    }
}
