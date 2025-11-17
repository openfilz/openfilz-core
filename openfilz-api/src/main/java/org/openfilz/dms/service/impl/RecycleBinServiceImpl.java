package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.exception.DocumentNotFoundException;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.repository.impl.DocumentSoftDeleteDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.openfilz.dms.service.RecycleBinService;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.openfilz.dms.enums.DocumentType.FILE;
import static org.openfilz.dms.enums.DocumentType.FOLDER;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.soft-delete.active", havingValue = "true")
public class RecycleBinServiceImpl implements RecycleBinService, UserInfoService {

    private final DocumentDAO documentDAO;
    private final MetadataPostProcessor metadataPostProcessor;
    private final DocumentSoftDeleteDAO documentSoftDeleteDAO;
    private final StorageService storageService;
    private final AuditService auditService;
    private final TransactionalOperator tx;

    @Override
    public Flux<FolderElementInfo> listDeletedItems() {
        return getConnectedUserEmail()
                .flatMapMany(userId -> documentSoftDeleteDAO.findDeletedDocuments(userId));
    }

    @Override
    public Mono<Void> restoreItems(List<UUID> documentIds) {
        return Flux.fromIterable(documentIds)
                .flatMap(docId -> documentDAO.findById(docId, null) // Find even if deleted
                        .switchIfEmpty(Mono.error(new DocumentNotFoundException(docId)))
                        .flatMap(doc -> {
                            // Determine if it's a file or folder
                            DocumentType type = doc.getType();
                            AuditAction action = type == FILE ? AuditAction.RESTORE_FILE : AuditAction.RESTORE_FOLDER;

                            // Restore recursively if it's a folder
                            Mono<Void> restore = type == FOLDER
                                    ? documentSoftDeleteDAO.restoreRecursive(docId)
                                    : documentSoftDeleteDAO.restore(docId);

                            return restore.then(auditService.logAction(action, type, docId));
                        })
                        .as(tx::transactional)
                )
                .then();
    }

    @Override
    public Mono<Void> permanentlyDeleteItems(List<UUID> documentIds) {
        return getConnectedUserEmail()
                .flatMap(userId -> Flux.fromIterable(documentIds)
                        .flatMap(docId -> documentDAO.findById(docId, null) // Find even if deleted
                                .switchIfEmpty(Mono.error(new DocumentNotFoundException(docId)))
                                .flatMap(doc -> permanentlyDeleteDocumentRecursive(doc, userId))
                        )
                        .then()
                );
    }

    private Mono<Void> permanentlyDeleteDocumentRecursive(Document document, String userId) {
        UUID docId = document.getId();
        DocumentType type = document.getType();
        AuditAction action = type == FILE ? AuditAction.PERMANENT_DELETE_FILE : AuditAction.PERMANENT_DELETE_FOLDER;

        if (type == FILE) {
            // Delete physical file and database record
            return storageService.deleteFile(document.getStoragePath())
                    .then(documentSoftDeleteDAO.permanentDelete(docId))
                    .then(auditService.logAction(action, type, docId))
                    .as(tx::transactional)
                    .doOnSuccess(_ -> metadataPostProcessor.deleteDocument(docId));
        } else {
            // For folders, recursively delete all children first
            return documentDAO.findDocumentsByParentIdAndType(docId, null) // Get all children (files and folders)
                    .flatMap(child -> permanentlyDeleteDocumentRecursive(child, userId))
                    .then(documentSoftDeleteDAO.permanentDelete(docId))
                    .then(auditService.logAction(action, type, docId))
                    .as(tx::transactional)
                    .doOnSuccess(_ -> metadataPostProcessor.deleteDocument(docId));
        }
    }

    /*private Mono<Void> deleteFolderRecursive(UUID folderId) {
        return documentDAO.getFolderToDelete(folderId)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(FOLDER, folderId)))
                .flatMap(folder -> {
                    // 1. Delete child files
                    Mono<Void> deleteChildFiles = getChildrenDocumentsToDelete(folderId, FILE)
                            .flatMap(file -> storageService.deleteFile(file.getStoragePath())
                                    .then(documentDAO.delete(file))
                                    .then(auditService.logAction(DELETE_FILE_CHILD, FILE, file.getId(), new DeleteAudit(folderId)))
                                    .as(tx::transactional)
                                    .doOnSuccess(_ -> metadataPostProcessor.deleteDocument(file.getId()))
                            ).then();

                    // 2. Recursively delete child folders
                    Mono<Void> deleteChildFolders = getChildrenDocumentsToDelete(folderId, FOLDER)
                            .flatMap(childFolder -> deleteFolderRecursive(childFolder.getId()))
                            .then();

                    // 3. Delete the folder itself from DB (and storage if it had a physical representation)
                    return Mono.when(deleteChildFiles, deleteChildFolders)
                            .then(documentDAO.delete(folder))
                            .then(auditService.logAction(AuditAction.DELETE_FOLDER, FOLDER, folderId))
                            .as(tx::transactional)
                            .doOnSuccess(_ -> metadataPostProcessor.deleteDocument(folder.getId()));
                });
    }*/

    @Override
    public Mono<Void> emptyRecycleBin() {
        return getConnectedUserEmail()
                .flatMap(userId -> documentSoftDeleteDAO.findDeletedDocuments(userId)
                        .map(FolderElementInfo::id)
                        .collectList()
                        .flatMap(docIds -> {
                            if (docIds.isEmpty()) {
                                return Mono.empty();
                            }
                            return permanentlyDeleteItems(docIds)
                                    .then(auditService.logAction(AuditAction.EMPTY_RECYCLE_BIN, null, null));
                        })
                );
    }

    @Override
    public Mono<Long> countDeletedItems() {
        return getConnectedUserEmail()
                .flatMap(documentSoftDeleteDAO::countDeletedDocuments);
    }
}
