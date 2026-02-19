package org.openfilz.dms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.QuotaProperties;
import org.openfilz.dms.dto.audit.UploadAudit;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.dto.Checksum;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.ChecksumService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.utils.ContentInfo;
import org.openfilz.dms.utils.FileUtils;
import org.openfilz.dms.utils.JsonUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

import static org.openfilz.dms.enums.DocumentType.FILE;
import static org.openfilz.dms.service.ChecksumService.HASH_SHA256_KEY;

@Service
@Slf4j
@ConditionalOnProperty(name = "openfilz.calculate-checksum", havingValue = "true")
public class ChecksumSaveDocumentServiceImpl extends SaveDocumentServiceImpl {
    private final ChecksumService checksumService;

    public ChecksumSaveDocumentServiceImpl(StorageService storageService, ObjectMapper objectMapper, AuditService auditService, JsonUtils jsonUtils, DocumentDAO documentDAO, MetadataPostProcessor metadataPostProcessor, TransactionalOperator tx, QuotaProperties quotaProperties, ChecksumService checksumService) {
        super(storageService, objectMapper, auditService, jsonUtils, documentDAO, metadataPostProcessor, tx, quotaProperties);
        this.checksumService = checksumService;
    }


    @Override
    public Mono<UploadResponse> doSaveFile(FilePart filePart, Long contentLength, UUID parentFolderId, Map<String, Object> metadata, String originalFilename, Mono<String> storagePathMono) {
        return storagePathMono
                .flatMap(storagePath -> checksumService.calculateChecksum(storagePath, metadata))
                .flatMap(checksum -> saveDocumentInDatabase(filePart, contentLength, parentFolderId, checksum.metadataWithChecksum(), originalFilename, checksum.storagePath()))
                .flatMap(savedDoc -> auditService.logAction(AuditAction.UPLOAD_DOCUMENT, FILE, savedDoc.getId(), new UploadAudit(savedDoc.getName(), parentFolderId, metadata)).thenReturn(savedDoc))
                .as(tx::transactional)
                .flatMap(savedDoc -> Mono.just(new UploadResponse(savedDoc.getId(), savedDoc.getName(), savedDoc.getContentType(), savedDoc.getSize()))
                        .doOnSuccess(_ -> postProcessDocument(savedDoc)));
    }

    @Override
    protected Mono<Document> replaceFileContentAndSave(FilePart newFilePart, ContentInfo contentInfo, Document document, String newStoragePath, String oldStoragePath) {
        Json metadata = document.getMetadata();
        if(contentInfo.checksum() != null) {
            Map<String, Object> metadataMap = metadata != null ? jsonUtils.toMap(metadata) : null;
            Map<String, Object> metadataWithChecksum = FileUtils.getMetadataWithChecksum(metadataMap, contentInfo.checksum());
            document.setMetadata(jsonUtils.toJson(metadataWithChecksum));
            return super.replaceFileContentAndSave(newFilePart, contentInfo, document, newStoragePath, oldStoragePath);
        }
        return checksumService.calculateChecksum(newStoragePath, metadata != null ? jsonUtils.toMap(metadata) : null)
                .flatMap(checksum -> {
                    document.setMetadata(jsonUtils.toJson(checksum.metadataWithChecksum()));
                    return super.replaceFileContentAndSave(newFilePart, contentInfo, document, newStoragePath, oldStoragePath);
                });
    }

    @Override
    public Mono<Document> saveAndReplaceDocument(FilePart newFilePart, ContentInfo contentInfo, Document document, String oldStoragePath) {
        return storageService.replaceFile(oldStoragePath, newFilePart)
                .flatMap(newStoragePath -> {
                    if (newStoragePath.equals(oldStoragePath)) {
                        // Versioning mode: file was replaced in-place (new version of same object)
                        return handleVersionedReplace(newFilePart, contentInfo, document, newStoragePath);
                    }
                    // Non-versioning mode: a new file was created with a different path
                    return checkNewFileIsDifferentFromExisting(newStoragePath, contentInfo,
                            getChecksum(document), oldStoragePath)
                            .flatMap(checksumInfo -> {
                                if (!checksumInfo.isSame()) {
                                    log.debug("the new file is not the same as the existing one");
                                    return replaceFileContentAndSave(newFilePart, new ContentInfo(contentInfo.length(), checksumInfo.newValue()), document, newStoragePath, oldStoragePath);
                                }
                                log.debug("the new file is the same as the existing one");
                                return storageService.deleteFile(newStoragePath).thenReturn(document);
                            });
                });
    }

    private Mono<Document> handleVersionedReplace(FilePart newFilePart, ContentInfo contentInfo, Document document, String storagePath) {
        // Calculate the new file's checksum (from client-provided value or from storage)
        Mono<String> newChecksumMono = contentInfo != null && contentInfo.checksum() != null
                ? Mono.just(contentInfo.checksum())
                : checksumService.calculateChecksum(storagePath, null).map(Checksum::hash);

        return newChecksumMono.flatMap(newHash -> {
            String existingChecksum = getChecksum(document);
            if (existingChecksum != null) {
                return compareAndHandleVersioned(newFilePart, contentInfo, document, storagePath, newHash, existingChecksum);
            }
            // No existing checksum in document metadata - calculate from previous version in MinIO
            return checksumService.calculatePreviousVersionChecksum(storagePath)
                    .flatMap(oldHash -> compareAndHandleVersioned(newFilePart, contentInfo, document, storagePath, newHash, oldHash))
                    // If no previous version checksum available, treat as different content
                    .switchIfEmpty(Mono.defer(() -> {
                        log.debug("Versioned replace: no previous version checksum available, treating as different");
                        return replaceFileContentAndSave(newFilePart,
                                new ContentInfo(contentInfo != null ? contentInfo.length() : null, newHash),
                                document, storagePath, storagePath);
                    }));
        });
    }

    private Mono<Document> compareAndHandleVersioned(FilePart newFilePart, ContentInfo contentInfo,
                                                     Document document, String storagePath,
                                                     String newHash, String oldHash) {
        if (newHash.equals(oldHash)) {
            log.debug("Versioned replace: new file is identical to previous version, reverting");
            return storageService.deleteLatestVersion(storagePath).thenReturn(document);
        }
        log.debug("Versioned replace: new file differs from previous version, updating document");
        return replaceFileContentAndSave(newFilePart,
                new ContentInfo(contentInfo != null ? contentInfo.length() : null, newHash),
                document, storagePath, storagePath);
    }

    private Mono<ChecksumInfo> checkNewFileIsDifferentFromExisting(String newStoragePath, ContentInfo contentInfo, String checksum, String oldStoragePath) {
        log.debug("contentInfo.checksum() = {} - checksum = {}", contentInfo.checksum(), checksum);
        if(contentInfo.checksum() != null && checksum != null) {
            return Mono.just(new ChecksumInfo(contentInfo.checksum().equals(checksum), contentInfo.checksum()));
        }
        if(contentInfo.checksum() == null) {
            return checksumService.calculateChecksum(newStoragePath, null)
                    .flatMap(newChecksum -> {
                       if(checksum != null) {
                           return Mono.just(new ChecksumInfo(newChecksum.hash().equals(checksum), newChecksum.hash()));
                       }
                       return checksumService.calculateChecksum(oldStoragePath, null)
                               .flatMap(oldChecksum -> Mono.just(new ChecksumInfo(newChecksum.hash().equals(oldChecksum.hash()),  newChecksum.hash())));
                    });
        }
        return checksumService.calculateChecksum(oldStoragePath, null)
                .flatMap(oldChecksum -> Mono.just(new ChecksumInfo(contentInfo.checksum().equals(oldChecksum.hash()), contentInfo.checksum())));
    }

    private record ChecksumInfo(boolean isSame, String newValue) {}

    protected String getChecksum(Document document) {
        Json metadata = document.getMetadata();
        if(metadata != null) {
            Object sha = jsonUtils.toMap(metadata).get(HASH_SHA256_KEY);
            return sha != null ? sha.toString() : null;
        };
        return null;
    }

}
