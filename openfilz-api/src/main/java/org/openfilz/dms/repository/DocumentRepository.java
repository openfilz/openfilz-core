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
}