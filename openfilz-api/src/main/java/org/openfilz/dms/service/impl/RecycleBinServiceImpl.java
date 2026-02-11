package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.QuotaProperties;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.exception.DocumentNotFoundException;
import org.openfilz.dms.exception.UserQuotaExceededException;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.repository.DocumentRepository;
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

    private final DocumentRepository documentRepository;
    private final DocumentDAO documentDAO;
    private final MetadataPostProcessor metadataPostProcessor;
    private final DocumentSoftDeleteDAO documentSoftDeleteDAO;
    private final StorageService storageService;
    private final AuditService auditService;
    private final TransactionalOperator tx;
    private final QuotaProperties quotaProperties;

    @Override
    public Flux<FolderElementInfo> listDeletedItems() {
        return documentSoftDeleteDAO.findDeletedDocuments();
    }

    @Override
    public Mono<Void> restoreItems(List<UUID> documentIds) {
        Mono<Void> quotaCheck = Mono.empty();

        if (quotaProperties.isUserQuotaEnabled()) {
            Long maxQuota = quotaProperties.getUserQuotaInBytes();
            quotaCheck = getConnectedUserEmail()
                    .flatMap(username -> Mono.zip(
                            documentDAO.getTotalStorageByUser(username),
                            Flux.fromIterable(documentIds)
                                    .flatMap(documentSoftDeleteDAO::getTotalSizeToRestore)
                                    .reduce(0L, Long::sum)
                    ).flatMap(tuple -> {
                        long currentUsage = tuple.getT1();
                        long restoreSize = tuple.getT2();
                        if (currentUsage + restoreSize > maxQuota) {
                            return Mono.error(new UserQuotaExceededException(
                                    username, currentUsage, restoreSize, maxQuota));
                        }
                        return Mono.empty();
                    }));
        }

        return quotaCheck.then(Flux.fromIterable(documentIds)
                .flatMap(docId -> documentRepository.findById(docId) // Find even if deleted
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
                .then());
    }

    @Override
    public Mono<Void> permanentlyDeleteItems(List<UUID> documentIds) {
        return getConnectedUserEmail()
                .flatMap(userId -> Flux.fromIterable(documentIds)
                        .flatMap(docId -> documentRepository.findById(docId) // Find even if deleted
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
            return documentRepository.findByParentId(docId)
                    .flatMap(child -> permanentlyDeleteDocumentRecursive(child, userId))
                    .then(documentSoftDeleteDAO.permanentDelete(docId))
                    .then(auditService.logAction(action, type, docId))
                    .as(tx::transactional)
                    .doOnSuccess(_ -> metadataPostProcessor.deleteDocument(docId));
        }
    }

    @Override
    public Mono<Void> emptyRecycleBin() {
        return documentSoftDeleteDAO.findDeletedDocuments()
                    .map(FolderElementInfo::id)
                    .collectList()
                    .flatMap(docIds -> {
                        if (docIds.isEmpty()) {
                            return Mono.empty();
                        }
                        return permanentlyDeleteItems(docIds)
                                .then(auditService.logAction(AuditAction.EMPTY_RECYCLE_BIN, null, null));
                    });
    }

    @Override
    public Mono<Long> countDeletedItems() {
        return getConnectedUserEmail()
                .flatMap(documentSoftDeleteDAO::countDeletedDocuments);
    }
}
