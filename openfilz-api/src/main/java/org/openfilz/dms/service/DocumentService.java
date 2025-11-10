package org.openfilz.dms.service;

import org.openfilz.dms.dto.request.*;
import org.openfilz.dms.dto.response.*;
import org.openfilz.dms.entity.Document;
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

    Mono<Document> replaceDocumentContent(UUID documentId, FilePart newFilePart, Long contentLength);

    Mono<Document> replaceDocumentMetadata(UUID documentId, Map<String, Object> newMetadata);

    Mono<Document> updateDocumentMetadata(UUID documentId, UpdateMetadataRequest request);

    Mono<Void> deleteDocumentMetadata(UUID documentId, DeleteMetadataRequest request);

    Mono<? extends Resource> downloadDocument(Document document);

    Mono<Resource> downloadMultipleDocumentsAsZip(List<UUID> documentIds); // Complex: zipping

    Flux<UUID> searchDocumentIdsByMetadata(SearchByMetadataRequest request);

    Mono<Map<String, Object>> getDocumentMetadata(UUID documentId, SearchMetadataRequest request);

    Mono<Document> findDocumentToDownloadById(UUID documentIdentication); // Utility

    Mono<DocumentInfo> getDocumentInfo(UUID documentId, Boolean withMetadataentication);

    Flux<FolderElementInfo> listFolderInfo(UUID folderId, Boolean onlyFiles, Boolean onlyFoldersentication);

    Mono<Long> countFolderElements(UUID folderIdentication);
}