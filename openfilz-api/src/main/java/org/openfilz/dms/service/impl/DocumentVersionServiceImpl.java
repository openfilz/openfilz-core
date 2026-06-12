package org.openfilz.dms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.audit.RestoreVersionAudit;
import org.openfilz.dms.dto.response.DocumentVersionInfo;
import org.openfilz.dms.dto.response.DownloadableVersion;
import org.openfilz.dms.dto.response.RestoreVersionResponse;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AccessType;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.exception.CannotRestoreLatestVersionException;
import org.openfilz.dms.exception.DocumentNotFoundException;
import org.openfilz.dms.exception.OperationForbiddenException;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.ChecksumService;
import org.openfilz.dms.service.DocumentVersionService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.utils.JsonUtils;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.UUID;

import static org.openfilz.dms.enums.DocumentType.FILE;

/**
 * Real implementation, active when {@code storage.type=minio} and
 * {@code storage.minio.versioning-enabled=true} (selected at runtime by
 * {@link org.openfilz.dms.config.DocumentVersionConfig} — native-image safe).
 */
@Slf4j
@Service
@Lazy
@Qualifier("versioningEnabled")
public class DocumentVersionServiceImpl implements DocumentVersionService, UserInfoService {

    private final DocumentDAO documentDAO;
    private final StorageService storageService;
    private final AuditService auditService;
    private final MetadataPostProcessor metadataPostProcessor;
    private final TransactionalOperator tx;
    private final JsonUtils jsonUtils;
    private final ObjectProvider<ChecksumService> checksumServiceProvider;

    @Value("${openfilz.calculate-checksum:false}")
    private Boolean calculateChecksum;

    public DocumentVersionServiceImpl(DocumentDAO documentDAO, StorageService storageService,
                                      AuditService auditService, MetadataPostProcessor metadataPostProcessor,
                                      TransactionalOperator tx, JsonUtils jsonUtils,
                                      ObjectProvider<ChecksumService> checksumServiceProvider) {
        this.documentDAO = documentDAO;
        this.storageService = storageService;
        this.auditService = auditService;
        this.metadataPostProcessor = metadataPostProcessor;
        this.tx = tx;
        this.jsonUtils = jsonUtils;
        this.checksumServiceProvider = checksumServiceProvider;
    }

    @Override
    public Flux<DocumentVersionInfo> listVersions(UUID documentId) {
        return findFileDocument(documentId, AccessType.RO)
                .flatMapMany(document -> storageService.listFileVersions(document.getStoragePath()))
                .sort(Comparator.comparing(DocumentVersionInfo::lastModified,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed());
    }

    @Override
    public Mono<DownloadableVersion> downloadVersion(UUID documentId, String versionId) {
        return findFileDocument(documentId, AccessType.RO)
                .flatMap(document -> requireVersion(document, versionId)
                        .flatMap(version -> storageService.loadFileVersion(document.getStoragePath(), versionId)
                                .map(resource -> new DownloadableVersion(document.getName(), document.getContentType(), resource))));
    }

    @Override
    public Mono<RestoreVersionResponse> restoreVersion(UUID documentId, String versionId) {
        return findFileDocument(documentId, AccessType.RWD)
                .flatMap(document -> requireVersion(document, versionId)
                        .flatMap(version -> {
                            if (version.latest()) {
                                return Mono.error(new CannotRestoreLatestVersionException(versionId));
                            }
                            return storageService.restoreFileVersion(document.getStoragePath(), versionId)
                                    .flatMap(newVersionId -> updateDocumentAfterRestore(document, version, newVersionId));
                        }));
    }

    private Mono<RestoreVersionResponse> updateDocumentAfterRestore(Document document, DocumentVersionInfo restored, String newVersionId) {
        return recomputeChecksum(document)
                .flatMap(doc -> getConnectedUserEmail().flatMap(username -> {
                    doc.setSize(restored.size());
                    doc.setUpdatedAt(OffsetDateTime.now());
                    doc.setUpdatedBy(username);
                    return documentDAO.update(doc);
                }))
                .flatMap(updatedDoc -> auditService.logAction(AuditAction.RESTORE_DOCUMENT_VERSION, FILE, updatedDoc.getId(),
                                new RestoreVersionAudit(updatedDoc.getName(), restored.versionId(), restored.lastModified(), newVersionId))
                        .thenReturn(updatedDoc))
                .as(tx::transactional)
                // content changed: refresh full-text index / thumbnails like a replace does
                .doOnSuccess(metadataPostProcessor::processDocument)
                .map(updatedDoc -> new RestoreVersionResponse(updatedDoc.getId(), restored.versionId(), restored.lastModified(), newVersionId));
    }

    /**
     * The restored content differs from the previous latest version, so the stored
     * sha256 metadata must be recomputed when the checksum feature is active.
     */
    private Mono<Document> recomputeChecksum(Document document) {
        if (!Boolean.TRUE.equals(calculateChecksum)) {
            return Mono.just(document);
        }
        ChecksumService checksumService = checksumServiceProvider.getIfAvailable();
        if (checksumService == null) {
            return Mono.just(document);
        }
        return checksumService.calculateChecksum(document.getStoragePath(),
                        document.getMetadata() != null ? jsonUtils.toMap(document.getMetadata()) : null)
                .map(checksum -> {
                    document.setMetadata(jsonUtils.toJson(checksum.metadataWithChecksum()));
                    return document;
                })
                .defaultIfEmpty(document);
    }

    private Mono<Document> findFileDocument(UUID documentId, AccessType accessType) {
        return documentDAO.findById(documentId, accessType)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(documentId)))
                .filter(document -> document.getType() == FILE)
                .switchIfEmpty(Mono.error(new OperationForbiddenException("Versions are only available for files : " + documentId)));
    }

    private Mono<DocumentVersionInfo> requireVersion(Document document, String versionId) {
        return storageService.listFileVersions(document.getStoragePath())
                .filter(version -> versionId.equals(version.versionId()))
                .next()
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(
                        "Version " + versionId + " not found for document : " + document.getId())));
    }
}
