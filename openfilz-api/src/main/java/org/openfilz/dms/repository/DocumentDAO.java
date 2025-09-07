package org.openfilz.dms.repository;

import jakarta.annotation.Nonnull;
import org.openfilz.dms.dto.request.SearchByMetadataRequest;
import org.openfilz.dms.dto.response.ChildElementInfo;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

public interface DocumentDAO {
    Flux<UUID> listDocumentIds(Authentication authentication, SearchByMetadataRequest request);

    Flux<ChildElementInfo> getChildren(UUID folderId);

    Flux<ChildElementInfo> getElementsAndChildren(List<UUID> documentIds);

    Flux<FolderElementInfo> listDocumentInfoInFolder(Authentication authentication, UUID parentFolderId, DocumentType type);

    Mono<Long> countDocument(Authentication authentication, UUID parentId);

    Mono<Boolean> existsByNameAndParentId(Authentication authentication, String name, UUID parentId);

    Mono<Boolean> existsByIdAndType(Authentication authentication, UUID id, DocumentType type);

    Mono<Document> getFolderToDelete(Authentication auth, UUID folderId);

    Flux<Document> findDocumentsByParentIdAndType(Authentication auth, @Nonnull UUID folderId, @Nonnull DocumentType documentType);

    Mono<Document> findById(UUID documentId, Authentication authentication);

    Mono<Document> update(Document document);

    Mono<Void> delete(Document document);

    Mono<Document> create(Document document);
}
