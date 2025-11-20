package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.audit.DeleteAudit;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AccessType;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.exception.DocumentNotFoundException;
import org.openfilz.dms.exception.OperationForbiddenException;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.DocumentDeleteService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.openfilz.dms.service.StorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.openfilz.dms.enums.AuditAction.DELETE_FILE_CHILD;
import static org.openfilz.dms.enums.DocumentType.FILE;
import static org.openfilz.dms.enums.DocumentType.FOLDER;

@Service
@RequiredArgsConstructor
@ConditionalOnProperties(value = {
        @ConditionalOnProperty(name = "openfilz.soft-delete.active", matchIfMissing = true, havingValue = "false"),
        @ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
})
public class DocumentDefaultDeleteService implements DocumentDeleteService {


    protected final DocumentDAO documentDAO;
    protected final TransactionalOperator tx;
    protected final StorageService storageService;
    protected final AuditService auditService;
    protected final MetadataPostProcessor metadataPostProcessor;


    @Override
    public Mono<Void> deleteFiles(DeleteRequest request) {
        return Flux.fromIterable(request.documentIds())
                .flatMap(docId -> documentDAO.findById(docId, AccessType.RWD)
                        .switchIfEmpty(Mono.error(new DocumentNotFoundException(docId)))
                        .filter(doc -> doc.getType() == FILE) // Ensure it's a file
                        .switchIfEmpty(Mono.error(new OperationForbiddenException("ID " + docId + " is a folder. Use delete folders API.")))
                        .flatMap(document -> storageService.deleteFile(document.getStoragePath())
                                .then(documentDAO.delete(document)))
                        .then(auditService.logAction(AuditAction.DELETE_FILE, FILE, docId))
                        .as(tx::transactional)
                        .doOnSuccess(_ -> metadataPostProcessor.deleteDocument(docId))
                )
                .then();
    }

    @Override
    public Mono<Void> deleteFolderRecursive(UUID folderId) {
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
    }

    protected Flux<Document> getChildrenDocumentsToDelete(UUID folderId, DocumentType docType) {
        return documentDAO.findDocumentsByParentIdAndType(folderId, docType);
    }

}

