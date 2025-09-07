// com/example/dms/service/DocumentService.java
package org.openfilz.dms.service;

import org.openfilz.dms.dto.request.*;
import org.openfilz.dms.dto.response.*;
import org.openfilz.dms.entity.Document;
import org.springframework.core.io.Resource;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DocumentService {
    // Folder Operations
    Mono<FolderResponse> createFolder(CreateFolderRequest request, Authentication auth);

    Mono<Void> moveFolders(MoveRequest request, Authentication auth);

    Flux<UUID> copyFolders(CopyRequest request, Authentication auth); // Complex: involves deep copy

    Mono<Document> renameFolder(UUID folderId, RenameRequest request, Authentication auth);

    Mono<Void> deleteFolders(DeleteRequest request, Authentication auth); // Complex: involves recursive deletion

    // File Operations
    Mono<Void> moveFiles(MoveRequest request, Authentication auth);

    Flux<CopyResponse> copyFiles(CopyRequest request, Authentication auth);

    Mono<Document> renameFile(UUID fileId, RenameRequest request, Authentication auth);

    Mono<Void> deleteFiles(DeleteRequest request, Authentication auth);

    // Document (File/Folder) Operations
    Mono<UploadResponse> uploadDocument(FilePart filePart, Long contentLength, UUID parentFolderId, Map<String, Object> metadata, Boolean allowDuplicateFileNames, Authentication auth);

    Mono<Document> replaceDocumentContent(UUID documentId, FilePart newFilePart, Long contentLength, Authentication auth);

    Mono<Document> replaceDocumentMetadata(UUID documentId, Map<String, Object> newMetadata, Authentication auth);

    Mono<Document> updateDocumentMetadata(UUID documentId, UpdateMetadataRequest request, Authentication auth);

    Mono<Void> deleteDocumentMetadata(UUID documentId, DeleteMetadataRequest request, Authentication auth);

    Mono<? extends Resource> downloadDocument(Document document, Authentication auth);

    Mono<Resource> downloadMultipleDocumentsAsZip(List<UUID> documentIds, Authentication auth); // Complex: zipping

    Flux<UUID> searchDocumentIdsByMetadata(SearchByMetadataRequest request, Authentication auth);

    Mono<Map<String, Object>> getDocumentMetadata(UUID documentId, SearchMetadataRequest request, Authentication auth);

    Mono<Document> findDocumentToDownloadById(UUID documentId, Authentication authentication); // Utility

    Mono<DocumentInfo> getDocumentInfo(UUID documentId, Boolean withMetadata, Authentication authentication);

    Flux<FolderElementInfo> listFolderInfo(UUID folderId, Boolean onlyFiles, Boolean onlyFolders, Authentication authentication);

    Mono<Long> countFolderElements(UUID folderId, Authentication authentication);
}