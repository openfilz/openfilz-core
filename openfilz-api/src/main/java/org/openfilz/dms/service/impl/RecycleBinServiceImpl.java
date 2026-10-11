package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.service.quota.StorageQuotaService;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.OpenSearchDocumentKey;
import org.openfilz.dms.exception.DocumentNotFoundException;
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

    private static final String ACTIVE_KEY = OpenSearchDocumentKey.active.toString();

    private final DocumentRepository documentRepository;
    private final DocumentDAO documentDAO;
    private final MetadataPostProcessor metadataPostProcessor;
    private final DocumentSoftDeleteDAO documentSoftDeleteDAO;
    private final StorageService storageService;
    private final AuditService auditService;
    private final TransactionalOperator tx;
    private final StorageQuotaService storageQuotaService;

    @Override
    public Flux<FolderElementInfo> listDeletedItems() {
        return documentSoftDeleteDAO.findDeletedDocuments();
    }

    @Override
    public Mono<Void> restoreItems(List<UUID> documentIds) {
        // Resolve (and check) every id first: an id the caller may not act on fails the whole request
        // before any size is summed or any row is touched.
        return Flux.fromIterable(documentIds)
                .concatMap(this::findDeletedDocument)
                .collectList()
                .flatMap(docs -> {
                    // Restoring brings files back into the caller's (and the instance's) usage.
                    Mono<Void> quotaCheck = Flux.fromIterable(docs)
                            .flatMap(doc -> documentSoftDeleteDAO.getTotalSizeToRestore(doc.getId()))
                            .reduce(0L, Long::sum)
                            .flatMap(storageQuotaService::checkStorage);
                    return quotaCheck.then(Flux.fromIterable(docs).flatMap(this::restoreDocument).then());
                });
    }

    private Mono<Void> restoreDocument(Document doc) {
        UUID docId = doc.getId();
        // Determine if it's a file or folder
        DocumentType type = doc.getType();
        AuditAction action = type == FILE ? AuditAction.RESTORE_FILE : AuditAction.RESTORE_FOLDER;

        if (type == FOLDER) {
            return documentSoftDeleteDAO.restoreRecursive(docId)
                    .then(auditService.logAction(action, type, docId))
                    .as(tx::transactional)
                    .thenMany(documentSoftDeleteDAO.findDescendantIds(docId))
                    .doOnNext(id -> metadataPostProcessor.updateIndexField(id, ACTIVE_KEY, true))
                    .then();
        }

        return documentSoftDeleteDAO.restore(docId)
                .then(auditService.logAction(action, type, docId))
                .as(tx::transactional)
                .doOnSuccess(_ -> metadataPostProcessor.updateIndexField(doc, ACTIVE_KEY, true));
    }

    @Override
    public Mono<Void> permanentlyDeleteItems(List<UUID> documentIds) {
        return getConnectedUserEmail()
                .flatMap(userId -> Flux.fromIterable(documentIds)
                        .flatMap(docId -> findDeletedDocument(docId)
                                .flatMap(doc -> permanentlyDeleteDocumentRecursive(doc, userId))
                        )
                        .then()
                );
    }

    /**
     * The document behind a recycle-bin id. It must be soft-deleted (an active document is never
     * restored or purged through the bin, whatever role the caller holds) and the caller must be
     * allowed to act on it ({@link #mayRestoreOrPurge(Document)}). An unknown id, an active document
     * and a document the caller may not see all answer the same 404, so the bin is no existence oracle.
     */
    private Mono<Document> findDeletedDocument(UUID docId) {
        return documentRepository.findById(docId) // Find even if deleted
                .filter(doc -> Boolean.FALSE.equals(doc.getActive()))
                .filterWhen(this::mayRestoreOrPurge)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(docId)));
    }

    /**
     * Whether the caller may restore or permanently delete this soft-deleted document. The core
     * answers "the caller sees it in the recycle bin", with the same criteria the bin listing uses
     * ({@link DocumentSoftDeleteDAO#isVisibleInRecycleBin(UUID)}). An extension that scopes the bin
     * per caller tightens this hook (or that DAO method) — the core knows nothing about who else
     * may act on a document.
     */
    protected Mono<Boolean> mayRestoreOrPurge(Document document) {
        return documentSoftDeleteDAO.isVisibleInRecycleBin(document.getId());
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
            // For folders, recursively delete all children first — only those soft-deleted under it
            // (an active row under a bin folder is not the bin's to purge)
            return documentRepository.findByParentId(docId)
                    .filter(child -> Boolean.FALSE.equals(child.getActive()))
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
