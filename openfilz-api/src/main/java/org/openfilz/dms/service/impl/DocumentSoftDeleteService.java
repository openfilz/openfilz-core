package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.enums.AccessType;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.exception.DocumentNotFoundException;
import org.openfilz.dms.exception.OperationForbiddenException;
import org.openfilz.dms.enums.OpenSearchDocumentKey;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.repository.impl.DocumentSoftDeleteDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.DocumentDeleteService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.openfilz.dms.enums.DocumentType.FILE;
import static org.openfilz.dms.enums.DocumentType.FOLDER;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.soft-delete.active", havingValue = "true")
public class DocumentSoftDeleteService implements DocumentDeleteService, UserInfoService {

    private static final String ACTIVE_KEY = OpenSearchDocumentKey.active.toString();

    private final DocumentDAO documentDAO;
    private final DocumentSoftDeleteDAO documentSoftDeleteDAO;
    private final MetadataPostProcessor metadataPostProcessor;
    private final TransactionalOperator tx;
    private final AuditService auditService;

    @Override
    public Mono<Void> deleteFiles(DeleteRequest request) {
        return Flux.fromIterable(request.documentIds())
                    .flatMap(docId -> documentDAO.findById(docId, AccessType.RWD)
                            .switchIfEmpty(Mono.error(new DocumentNotFoundException(docId)))
                            .filter(doc -> doc.getType() == FILE) // Ensure it's a file
                            .switchIfEmpty(Mono.error(new OperationForbiddenException("ID " + docId + " is a folder. Use delete folders API.")))
                            // Soft delete - keep physical file, just mark as deleted in DB
                            .flatMap(doc -> documentSoftDeleteDAO.softDelete(docId)
                                    .then(auditService.logAction(AuditAction.DELETE_FILE, FILE, docId))
                                    .as(tx::transactional)
                                    .doOnSuccess(_ -> metadataPostProcessor.updateIndexField(doc, ACTIVE_KEY, false))
                            )
                    )
                .then();
    }

    @Override
    public Mono<Void> deleteFolderRecursive(UUID folderId) {
        return getConnectedUserEmail()
                .flatMap(userEmail -> documentDAO.getFolderToDelete(folderId)
                        .switchIfEmpty(Mono.error(new DocumentNotFoundException(FOLDER, folderId)))
                        .flatMap(folder ->
                            // Soft delete folder and all children recursively
                            // This marks the folder and all descendants as deleted without removing physical files
                            documentSoftDeleteDAO.softDeleteRecursive(folderId, userEmail)
                                    .then(auditService.logAction(AuditAction.DELETE_FOLDER, FOLDER, folderId))
                                    .as(tx::transactional)
                                    .thenMany(documentSoftDeleteDAO.findDescendantIds(folderId))
                                    .doOnNext(id -> metadataPostProcessor.updateIndexField(id, ACTIVE_KEY, false))
                                    .then()
                        ));
    }


}
