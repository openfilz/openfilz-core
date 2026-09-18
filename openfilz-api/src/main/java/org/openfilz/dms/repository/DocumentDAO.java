package org.openfilz.dms.repository;

import io.r2dbc.postgresql.codec.Json;
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

    Flux<Document> findDocumentsByParentId(@Nonnull UUID folderId);

    Mono<Document> findById(UUID documentId, AccessType accessType);

    Mono<Document> update(Document document);

    /**
     * Records a content replacement by writing <b>only</b> the content columns (storage path,
     * content type, size, updated at/by) and merging {@code metadataPatch} (may be {@code null})
     * into the stored metadata — never the whole row. A replacement loads the document, then
     * spends a while in storage before saving: a full-row save would put back whatever the
     * document looked like when it was loaded, silently undoing a move, rename or metadata
     * change committed meanwhile (an OCR replace reverting the smart-filing move of a fresh
     * upload is how this was found). Returns the row as stored after the update.
     */
    Mono<Document> updateContent(Document document, Json metadataPatch);

    /**
     * Moves a document by writing <b>only</b> its parent and updated at/by columns — the
     * mirror of {@link #updateContent}: a move must not put back a stale storage path, size
     * or checksum when a content replacement lands between its load and its save.
     */
    Mono<Document> updateParent(Document document);

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

    /**
     * Get the total storage size used by a user (sum of all file sizes created by the user).
     * This is an optimized query for quota enforcement.
     *
     * @param username The username (created_by field) to calculate storage for.
     * @return A Mono containing the total size in bytes (0 if no files exist).
     */
    Mono<Long> getTotalStorageByUser(String username);
}
