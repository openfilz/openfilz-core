// com/example/dms/repository/DocumentRepository.java
package org.openfilz.dms.repository;

import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface DocumentRepository extends ReactiveCrudRepository<Document, UUID> {

    // Filter out soft-deleted items (deleted_at IS NULL)
    Flux<Document> findByParentIdAndTypeAndDeletedAtIsNull(UUID parentId, DocumentType type);

    Mono<Document> findByIdAndTypeAndDeletedAtIsNull(UUID id, DocumentType documentType);

    Mono<Boolean> existsByIdAndTypeAndDeletedAtIsNull(UUID id, DocumentType type);

    Mono<Boolean> existsByNameAndParentIdIsNullAndDeletedAtIsNull(String name);

    Mono<Boolean> existsByNameAndParentIdAndDeletedAtIsNull(String name, UUID parentId);

    Mono<Long> countDocumentByParentIdIsNullAndDeletedAtIsNull();

    Mono<Long> countDocumentByParentIdEqualsAndDeletedAtIsNull(UUID parentId);

    // Original methods for backward compatibility (include deleted items)
    Flux<Document> findByParentIdAndType(UUID parentId, DocumentType type);

    Mono<Document> findByIdAndType(UUID id, DocumentType documentType);

    Mono<Boolean> existsByIdAndType(UUID id, DocumentType type);

    Mono<Boolean> existsByNameAndParentIdIsNull(String name);

    Mono<Boolean> existsByNameAndParentId(String name, UUID parentId);

    Mono<Long> countDocumentByParentIdIsNull();

    Mono<Long> countDocumentByParentIdEquals(UUID parentId);
}