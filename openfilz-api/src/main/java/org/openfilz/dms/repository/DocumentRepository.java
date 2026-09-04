package org.openfilz.dms.repository;

import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface DocumentRepository extends ReactiveCrudRepository<Document, UUID> {

    Flux<Document> findByParentId(UUID parentId);

    Flux<Document> findByParentIdAndType(UUID parentId, DocumentType type);

    Mono<Document> findByIdAndType(UUID id, DocumentType documentType);

    Mono<Long> countDocumentByParentIdIsNullAndActiveIsTrue();

    Mono<Long> countDocumentByParentIdEqualsAndActiveIsTrue(UUID parentId);

    Mono<Boolean> existsByIdAndTypeAndActive(UUID id, DocumentType type, boolean active);

    Mono<Document> findByIdAndActive(UUID documentId, boolean active);

    Mono<Boolean> existsByNameAndParentIdIsNullAndActiveIsTrue(String name);

    Mono<Boolean> existsByNameAndParentIdAndActiveIsTrue(String name, UUID parentId);

    /**
     * Name-fallback resolution for the AI tools: at most 50 active documents whose name contains
     * the fragment, by name. An unindexed {@code ILIKE '%...%'} scan, bounded on purpose.
     */
    Flux<Document> findTop50ByNameContainingIgnoreCaseAndActiveTrueOrderByNameAsc(String name);

    /** Active documents with exactly this name (case-insensitive): the reorganisation plan's name fallback. */
    Flux<Document> findByNameIgnoreCaseAndActiveTrue(String name);

    /** Active root-level documents (the reorganisation inventory walks the tree from here). */
    Flux<Document> findByParentIdIsNullAndActiveIsTrue();

    /** Active direct children of a folder. */
    Flux<Document> findByParentIdAndActiveIsTrue(UUID parentId);
}