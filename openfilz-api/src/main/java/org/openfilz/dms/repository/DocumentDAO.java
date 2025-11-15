package org.openfilz.dms.repository;

import jakarta.annotation.Nonnull;
import org.openfilz.dms.dto.request.SearchByMetadataRequest;
import org.openfilz.dms.dto.response.ChildElementInfo;
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

    Flux<FolderElementInfo> listDocumentInfoInFolder(UUID parentFolderId, DocumentType type, String userId);

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
     * Count total files by type
     */
    Mono<Long> countFilesByType(DocumentType type);

    /**
     * Get total storage used by content type pattern
     */
    Mono<Long> getTotalStorageByContentType(String contentTypePattern);

    /**
     * Count files by content type pattern
     */
    Mono<Long> countFilesByContentType(String contentTypePattern);

    /**
     * Get total storage used
     */
    Mono<Long> getTotalStorageUsed();

    // Recycle Bin Methods

    /**
     * Soft delete a document (set deleted_at and deleted_by)
     */
    Mono<Void> softDelete(UUID documentId, String userId);

    /**
     * Soft delete documents recursively (folder and all children)
     */
    Mono<Void> softDeleteRecursive(UUID documentId, String userId);

    /**
     * Find deleted documents for a specific user (recycle bin listing)
     */
    Flux<FolderElementInfo> findDeletedDocuments(String userId);

    /**
     * Restore a document (clear deleted_at and deleted_by)
     */
    Mono<Void> restore(UUID documentId);

    /**
     * Restore documents recursively (folder and all children)
     */
    Mono<Void> restoreRecursive(UUID documentId);

    /**
     * Permanently delete a document (hard delete from database)
     */
    Mono<Void> permanentDelete(UUID documentId);

    /**
     * Find documents that have been deleted longer than specified days
     * Used by cleanup scheduler
     */
    Flux<Document> findExpiredDeletedDocuments(int days);

    /**
     * Count deleted documents for a user
     */
    Mono<Long> countDeletedDocuments(String userId);
}
