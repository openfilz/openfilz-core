package org.openfilz.dms.service.impl;

import io.r2dbc.postgresql.codec.Json;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.audit.ReplaceAudit;
import org.openfilz.dms.dto.audit.UploadAudit;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.openfilz.dms.service.SaveDocumentService;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.quota.StorageQuotaService;
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
    protected final StorageQuotaService storageQuotaService;


   public Mono<UploadResponse> doSaveFile(FilePart filePart, Long contentLength, UUID parentFolderId, Map<String, Object> metadata, String originalFilename, Mono<String> storagePathMono) {
        return storagePathMono.flatMap(storagePath -> saveDocumentInDatabase(filePart, contentLength, parentFolderId, metadata, originalFilename, storagePath))
                .flatMap(savedDoc -> logUploadAction(savedDoc, parentFolderId, metadata).thenReturn(savedDoc))
                .as(tx::transactional)
                .flatMap(savedDoc -> Mono.just(new UploadResponse(savedDoc.getId(), savedDoc.getName(), savedDoc.getContentType(), savedDoc.getSize()))
                        .doOnSuccess(_ -> postProcessDocument(savedDoc)));
    }

    /**
     * Logs the UPLOAD_DOCUMENT audit entry, enriched with the storage versionId of the
     * newly created object when versioning is active (empty otherwise — see
     * {@link StorageService#getLatestVersionId(String)}).
     */
    protected Mono<Void> logUploadAction(Document savedDoc, UUID parentFolderId, Map<String, Object> metadata) {
        return storageService.getLatestVersionId(savedDoc.getStoragePath())
                .map(versionId -> new UploadAudit(savedDoc.getName(), parentFolderId, metadata, versionId))
                .defaultIfEmpty(new UploadAudit(savedDoc.getName(), parentFolderId, metadata))
                .flatMap(details -> auditService.logAction(AuditAction.UPLOAD_DOCUMENT, FILE, savedDoc.getId(), details));
    }

    protected void postProcessDocument(Document document) {
        metadataPostProcessor.processDocument(document);
    }

    protected Mono<Document> replaceFileContentAndSave(FilePart newFilePart, ContentInfo contentInfo, Document document, String newStoragePath, String oldStoragePath) {
        boolean quotaAlreadyChecked = contentInfo != null && contentInfo.length() != null;
        String checksum = contentInfo != null ? contentInfo.checksum() : null;
        long oldSize = document.getSize() != null ? document.getSize() : 0L;
        return storedFileLength(newStoragePath, newFilePart.filename(), quotaAlreadyChecked, oldSize,
                        // In place (bucket versioning): the object is the document itself, never delete it.
                        !newStoragePath.equals(oldStoragePath))
                .flatMap(fileLength -> replaceDocumentInDB(newFilePart, newStoragePath, oldStoragePath, new ContentInfo(fileLength, checksum), document))
                .doOnSuccess(this::postProcessDocument);
    }

    public Mono<Document> saveAndReplaceDocument(FilePart newFilePart, ContentInfo contentInfo, Document document, String oldStoragePath) {
        return storageService.replaceFile(oldStoragePath, newFilePart)
                .flatMap(newStoragePath ->
                        replaceFileContentAndSave(newFilePart, contentInfo, document, newStoragePath, oldStoragePath));
    }



    protected Mono<Document> saveDocumentInDatabase(FilePart filePart, Long contentLength, UUID parentFolderId, Map<String, Object> metadata, String originalFilename, String storagePath) {
        return storedFileLength(storagePath, originalFilename, contentLength != null, 0L, true)
                .flatMap(fileLength -> saveDocumentInDB(filePart, storagePath, fileLength, parentFolderId, metadata, originalFilename));
    }

    /**
     * The size recorded for a document is the length of the file that was stored — never the
     * request's Content-Length. That header measures the HTTP body: for a multipart upload, the
     * file plus its envelope (a few hundred bytes), and for several files in one request, all of
     * them together. It is good for refusing an oversized request before reading it, which is
     * what the caller already did when it had one; without it, the quotas are checked here,
     * against the real length.
     */
    private Mono<Long> storedFileLength(String storagePath, String filename, boolean quotaAlreadyChecked, long replacedBytes, boolean deleteOnRefusal) {
        return storageService.getFileLength(storagePath)
                .flatMap(fileLength -> quotaAlreadyChecked
                        ? Mono.just(fileLength)
                        : validateQuotasAfterStorage(fileLength, replacedBytes, filename, storagePath, deleteOnRefusal).thenReturn(fileLength));
    }

    /**
     * Validates the file size and storage quotas after storage, when the length was not known
     * before (no Content-Length). {@code replacedBytes} is the size of the content being replaced
     * (0 for a new document): only the growth counts against the storage quotas. When a quota is
     * exceeded, the stored file is deleted before the error goes out.
     */
    private Mono<Void> validateQuotasAfterStorage(Long fileLength, long replacedBytes, String filename, String storagePath, boolean deleteOnRefusal) {
        return storageQuotaService.checkFileSize(filename, fileLength)
                .then(storageQuotaService.checkStorage(fileLength - replacedBytes))
                .onErrorResume(e -> deleteOnRefusal ? storageService.deleteFile(storagePath).then(Mono.error(e)) : Mono.error(e));
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
                    // Content columns only: `document` was loaded before the storage upload, and a
                    // full-row save would revert a move/rename committed meanwhile.
                    return documentDAO.updateContent(document, contentMetadataPatch(document));
                })
                .flatMap(savedDoc -> {
                    // 3. Delete old file content from storage
                    if (oldStoragePath != null && !oldStoragePath.equals(newStoragePath)) {
                        return storageService.deleteFile(oldStoragePath).thenReturn(savedDoc);
                    }
                    return Mono.just(savedDoc);
                })
                .flatMap(updatedDoc -> storageService.getLatestVersionId(updatedDoc.getStoragePath())
                        .map(versionId -> new ReplaceAudit(newFilePart.filename(), versionId))
                        .defaultIfEmpty(new ReplaceAudit(newFilePart.filename()))
                        .flatMap(details -> auditService.logAction(REPLACE_DOCUMENT_CONTENT, FILE, updatedDoc.getId(), details))
                        .thenReturn(updatedDoc))
                .as(tx::transactional);

    }

    /**
     * The metadata keys a content replacement changes, merged into the stored metadata (the
     * rest of the stored metadata is left as it is). None in the base service.
     */
    protected Json contentMetadataPatch(Document document) {
        return null;
    }
}
