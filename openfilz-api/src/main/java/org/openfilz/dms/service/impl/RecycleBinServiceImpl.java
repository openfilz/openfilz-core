package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.exception.DocumentNotFoundException;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.RecycleBinService;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.openfilz.dms.enums.DocumentType.FILE;
import static org.openfilz.dms.enums.DocumentType.FOLDER;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecycleBinServiceImpl implements RecycleBinService, UserInfoService {

    private final DocumentDAO documentDAO;
    private final StorageService storageService;
    private final AuditService auditService;

    @Override
    public Flux<FolderElementInfo> listDeletedItems() {
        return getConnectedUserEmail()
                .flatMapMany(userId -> documentDAO.findDeletedDocuments(userId));
    }

    @Override
    @Transactional
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
                                    ? documentDAO.restoreRecursive(docId)
                                    : documentDAO.restore(docId);

                            return restore.then(auditService.logAction(action, type, docId));
                        })
                )
                .then();
    }

    @Override
    @Transactional
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
                    .then(documentDAO.permanentDelete(docId))
                    .then(auditService.logAction(action, type, docId));
        } else {
            // For folders, recursively delete all children first
            return documentDAO.findDocumentsByParentIdAndType(docId, null) // Get all children (files and folders)
                    .flatMap(child -> permanentlyDeleteDocumentRecursive(child, userId))
                    .then(documentDAO.permanentDelete(docId))
                    .then(auditService.logAction(action, type, docId));
        }
    }

    @Override
    @Transactional
    public Mono<Void> emptyRecycleBin() {
        return getConnectedUserEmail()
                .flatMap(userId -> documentDAO.findDeletedDocuments(userId)
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
                .flatMap(userId -> documentDAO.countDeletedDocuments(userId));
    }
}
