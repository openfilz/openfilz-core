package org.openfilz.dms.service;

import org.openfilz.dms.dto.request.*;
import org.openfilz.dms.dto.response.*;
import org.openfilz.dms.enums.DocumentTemplateType;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.utils.ContentInfo;
import org.springframework.core.io.Resource;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DocumentService {
    // Folder Operations
    Mono<FolderResponse> createFolder(CreateFolderRequest request);

    Mono<Void> moveFolders(MoveRequest request);

    Flux<UUID> copyFolders(CopyRequest request); // Complex: involves deep copy

    Mono<Document> renameFolder(UUID folderId, RenameRequest request);

    Mono<Void> deleteFolders(DeleteRequest request); // Complex: involves recursive deletion

    // File Operations
    Mono<Void> moveFiles(MoveRequest request);

    Flux<CopyResponse> copyFiles(CopyRequest request);

    Mono<Document> renameFile(UUID fileId, RenameRequest request);

    Mono<Void> deleteFiles(DeleteRequest request);

    // Document (File/Folder) Operations
    Mono<UploadResponse> uploadDocument(FilePart filePart, Long contentLength, UUID parentFolderId, Map<String, Object> metadata, Boolean allowDuplicateFileNames);

    /**
     * Create a blank document from a template.
     *
     * @param name           The name for the new document (including extension).
     * @param documentType   The type of document to create (WORD, EXCEL, POWERPOINT, TEXT).
     * @param parentFolderId The parent folder ID, or null for root.
     * @return A Mono containing the upload response with the new document's ID.
     */
    Mono<UploadResponse> createBlankDocument(String name, DocumentTemplateType documentType, UUID parentFolderId);

    Mono<Document> replaceDocumentContent(UUID documentId, FilePart newFilePart, ContentInfo contentInfo);

    Mono<Document> replaceDocumentMetadata(UUID documentId, Map<String, Object> newMetadata);

    Mono<Document> updateDocumentMetadata(UUID documentId, UpdateMetadataRequest request);

    Mono<Void> deleteDocumentMetadata(UUID documentId, DeleteMetadataRequest request);

    Mono<? extends Resource> downloadDocument(Document document);

    Mono<Resource> downloadMultipleDocumentsAsZip(List<UUID> documentIds); // Complex: zipping

    Flux<UUID> searchDocumentIdsByMetadata(SearchByMetadataRequest request);

    Mono<Map<String, Object>> getDocumentMetadata(UUID documentId, SearchMetadataRequest request);

    Mono<Document> findDocumentToDownloadById(UUID documentId); // Utility

    Mono<DocumentInfo> getDocumentInfo(UUID documentId, Boolean withMetadata);

    Flux<FolderElementInfo> listFolderInfo(UUID folderId, Boolean onlyFiles, Boolean onlyFolders);

    Mono<Long> countFolderElements(UUID folderId);

    /**
     * Get all ancestors (parent folders) of a document, ordered from root to immediate parent.
     *
     * @param documentId The UUID of the document.
     * @return A Flux of AncestorInfo ordered from root to immediate parent.
     */
    Flux<AncestorInfo> getDocumentAncestors(UUID documentId);

    /**
     * Get the position of a document within its parent folder.
     *
     * @param documentId The UUID of the document.
     * @param sortBy     The field to sort by.
     * @param sortOrder  The sort order ("ASC" or "DESC").
     * @return A Mono containing the document's position information.
     */
    Mono<DocumentPosition> getDocumentPosition(UUID documentId, String sortBy, String sortOrder);
}