package org.openfilz.dms.repository;

import jakarta.annotation.Nonnull;
import org.openfilz.dms.dto.request.SearchByMetadataRequest;
import org.openfilz.dms.dto.response.AncestorInfo;
import org.openfilz.dms.dto.response.ChildElementInfo;
import org.openfilz.dms.dto.response.DocumentPosition;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AccessType;
import org.openfilz.dms.enums.DocumentType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

public interface DocumentDAO {
    Flux<UUID> listDocumentIds(SearchByMetadataRequest request);

    Flux<ChildElementInfo> getChildren(UUID folderId);

    Flux<ChildElementInfo> getElementsAndChildren(List<UUID> documentIds, String connectedUserEmail);

    Flux<FolderElementInfo> listDocumentInfoInFolder(UUID parentFolderId, DocumentType type);

    Mono<Long> countDocument(UUID parentId);

    Mono<Boolean> existsByNameAndParentId(String name, UUID parentId);

    Mono<Boolean> existsByIdAndType(UUID id, DocumentType type, AccessType accessType);

    Mono<Document> getFolderToDelete(UUID folderId);

    Flux<Document> findDocumentsByParentIdAndType(@Nonnull UUID folderId, @Nonnull DocumentType documentType);

    Mono<Document> findById(UUID documentId, AccessType accessType);

    Mono<Document> update(Document document);

    Mono<Void> delete(Document document);

    Mono<Document> create(Document document);

    /**
     * Get all ancestors (parent folders) of a document, ordered from root to immediate parent.
     *
     * @param documentId The UUID of the document.
     * @return A Flux of AncestorInfo ordered from root to immediate parent.
     */
    Flux<AncestorInfo> getAncestors(UUID documentId);

    /**
     * Get the position of a document within its parent folder.
     *
     * @param documentId The UUID of the document.
     * @param sortBy     The field to sort by (e.g., "name", "updated_at").
     * @param sortOrder  The sort order ("ASC" or "DESC").
     * @return A Mono containing the document's position information.
     */
    Mono<DocumentPosition> getDocumentPosition(UUID documentId, String sortBy, String sortOrder);
}
