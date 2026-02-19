package org.openfilz.dms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.QuotaProperties;
import org.openfilz.dms.dto.audit.ReplaceAudit;
import org.openfilz.dms.dto.audit.UploadAudit;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.exception.FileSizeExceededException;
import org.openfilz.dms.exception.UserQuotaExceededException;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.openfilz.dms.service.SaveDocumentService;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.utils.FileUtils;
import org.openfilz.dms.utils.ContentInfo;
import org.openfilz.dms.utils.JsonUtils;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.openfilz.dms.enums.AuditAction.REPLACE_DOCUMENT_CONTENT;
import static org.openfilz.dms.enums.DocumentType.FILE;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.calculate-checksum", havingValue = "false", matchIfMissing = true)
public class SaveDocumentServiceImpl implements SaveDocumentService, UserInfoService {

    protected final StorageService storageService;
    protected final ObjectMapper objectMapper; // For JSONB processing
    protected final AuditService auditService; // For auditing
    protected final JsonUtils jsonUtils;
    protected final DocumentDAO documentDAO;
    protected final MetadataPostProcessor metadataPostProcessor;
    protected final TransactionalOperator tx;
    protected final QuotaProperties quotaProperties;


   public Mono<UploadResponse> doSaveFile(FilePart filePart, Long contentLength, UUID parentFolderId, Map<String, Object> metadata, String originalFilename, Mono<String> storagePathMono) {
        return storagePathMono.flatMap(storagePath -> saveDocumentInDatabase(filePart, contentLength, parentFolderId, metadata, originalFilename, storagePath))
                .flatMap(savedDoc -> auditService.logAction(AuditAction.UPLOAD_DOCUMENT, FILE, savedDoc.getId(), new UploadAudit(savedDoc.getName(), parentFolderId, metadata)).thenReturn(savedDoc))
                .as(tx::transactional)
                .flatMap(savedDoc -> Mono.just(new UploadResponse(savedDoc.getId(), savedDoc.getName(), savedDoc.getContentType(), savedDoc.getSize()))
                        .doOnSuccess(_ -> postProcessDocument(savedDoc)));
    }

    protected void postProcessDocument(Document document) {
        metadataPostProcessor.processDocument(document);
    }

    protected Mono<Document> replaceFileContentAndSave(FilePart newFilePart, ContentInfo contentInfo, Document document, String newStoragePath, String oldStoragePath) {
        if(contentInfo == null || contentInfo.length() == null) {
            // Content-Length was not provided - get actual size from storage and validate quota
            return storageService.getFileLength(newStoragePath)
                    .flatMap(fileLength -> validateFileSizeAfterStorage(fileLength, newFilePart.filename(), newStoragePath)
                            .then(replaceDocumentInDB(newFilePart,
                                    newStoragePath, oldStoragePath, new ContentInfo(fileLength, contentInfo != null ? contentInfo.checksum() : null), document)))
                    .doOnSuccess(this::postProcessDocument);
        }
        return replaceDocumentInDB(newFilePart, newStoragePath, oldStoragePath, contentInfo, document)
                .doOnSuccess(this::postProcessDocument);
    }

    public Mono<Document> saveAndReplaceDocument(FilePart newFilePart, ContentInfo contentInfo, Document document, String oldStoragePath) {
        return storageService.replaceFile(oldStoragePath, newFilePart)
                .flatMap(newStoragePath ->
                        replaceFileContentAndSave(newFilePart, contentInfo, document, newStoragePath, oldStoragePath));
    }



    protected Mono<Document> saveDocumentInDatabase(FilePart filePart, Long contentLength, UUID parentFolderId, Map<String, Object> metadata, String originalFilename, String storagePath) {
        if(contentLength == null) {
            // Content-Length was not provided - get actual size from storage and validate quota
            return storageService.getFileLength(storagePath)
                    .flatMap(fileLength -> validateFileSizeAfterStorage(fileLength, originalFilename, storagePath)
                            .then(saveDocumentInDB(filePart, storagePath, fileLength, parentFolderId, metadata, originalFilename)));
        }
        return saveDocumentInDB(filePart, storagePath, contentLength, parentFolderId, metadata, originalFilename);
    }

    /**
     * Validates file size and user quota after storage when Content-Length header was not available.
     * If any quota is exceeded, deletes the stored file and returns an error.
     */
    private Mono<Void> validateFileSizeAfterStorage(Long fileLength, String filename, String storagePath) {
        return validateFileUploadQuota(fileLength, filename, storagePath)
                .then(validateUserQuotaAfterStorage(fileLength, storagePath));
    }

    /**
     * Validates file upload quota (single file size limit).
     */
    private Mono<Void> validateFileUploadQuota(Long fileLength, String filename, String storagePath) {
        if (!quotaProperties.isFileUploadQuotaEnabled()) {
            return Mono.empty();
        }
        Long maxSize = quotaProperties.getFileUploadQuotaInBytes();
        if (fileLength > maxSize) {
            // Delete the file that was already stored, then return error
            return storageService.deleteFile(storagePath)
                    .then(Mono.error(new FileSizeExceededException(filename, fileLength, maxSize)));
        }
        return Mono.empty();
    }

    /**
     * Validates user quota after storage when Content-Length was not available.
     */
    private Mono<Void> validateUserQuotaAfterStorage(Long fileLength, String storagePath) {
        if (!quotaProperties.isUserQuotaEnabled()) {
            return Mono.empty();
        }
        Long maxQuota = quotaProperties.getUserQuotaInBytes();
        return getConnectedUserEmail()
                .flatMap(username -> documentDAO.getTotalStorageByUser(username)
                        .flatMap(currentUsage -> {
                            if (currentUsage + fileLength > maxQuota) {
                                // Delete the file that was already stored, then return error
                                return storageService.deleteFile(storagePath)
                                        .then(Mono.error(new UserQuotaExceededException(username, currentUsage, fileLength, maxQuota)));
                            }
                            return Mono.empty();
                        }));
    }

    private Mono<Document> saveDocumentInDB(FilePart filePart, String storagePath, Long contentLength, UUID parentFolderId, Map<String, Object> metadata, String originalFilename) {
        Document.DocumentBuilder documentBuilder = Document.builder()
                .name(originalFilename)
                .type(FILE)
                .contentType(FileUtils.getContentType(filePart))
                .size(contentLength)
                .parentId(parentFolderId)
                .storagePath(storagePath)
                .metadata(jsonUtils.toJson(metadata));
        return doSaveFile(saveNewDocumentFunction(documentBuilder));
    }

    public Function<String, Mono<Document>> saveNewDocumentFunction(Document.DocumentBuilder request) {
        return username -> {
            Document document = request
                    .createdAt(OffsetDateTime.now())
                    .updatedAt(OffsetDateTime.now())
                    .createdBy(username)
                    .updatedBy(username)
                    .build();
            return documentDAO.create(document);
        };
    }

    public Mono<Document> doSaveFile(Function<String, Mono<Document>> documentFunction) {
        return getConnectedUserEmail().flatMap(documentFunction);
    }



    protected Mono<Document> replaceDocumentInDB(FilePart newFilePart, String newStoragePath, String oldStoragePath, ContentInfo contentInfo, Document document) {
        return doSaveFile(username -> {
                    document.setStoragePath(newStoragePath);
                    document.setContentType(FileUtils.getContentType(newFilePart));
                    document.setUpdatedAt(OffsetDateTime.now());
                    document.setUpdatedBy(username);
                    document.setSize(contentInfo.length());
                    return documentDAO.update(document);
                })
                .flatMap(savedDoc -> {
                    // 3. Delete old file content from storage
                    if (oldStoragePath != null && !oldStoragePath.equals(newStoragePath)) {
                        return storageService.deleteFile(oldStoragePath).thenReturn(savedDoc);
                    }
                    return Mono.just(savedDoc);
                })
                .flatMap(updatedDoc -> auditService.logAction(REPLACE_DOCUMENT_CONTENT, FILE, updatedDoc.getId(),
                        new ReplaceAudit(newFilePart.filename())))
                .as(tx::transactional)
                .thenReturn(document);

    }
}
