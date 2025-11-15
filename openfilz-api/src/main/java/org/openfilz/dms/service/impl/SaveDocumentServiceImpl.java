package org.openfilz.dms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    protected Mono<Document> replaceFileContentAndSave(FilePart newFilePart, Long contentLength, Document document, String newStoragePath, String oldStoragePath) {
        if(contentLength == null) {
            return storageService.getFileLength(newStoragePath)
                    .flatMap(fileLength -> replaceDocumentInDB(newFilePart,
                            newStoragePath, oldStoragePath, fileLength,document))
                    .doOnSuccess(this::postProcessDocument);
        }
        return replaceDocumentInDB(newFilePart, newStoragePath, oldStoragePath, contentLength,document)
                .doOnSuccess(this::postProcessDocument);
    }

    public Mono<Document> saveAndReplaceDocument(FilePart newFilePart, Long contentLength, Document document, String oldStoragePath) {
        return storageService.saveFile(newFilePart)
                .flatMap(newStoragePath ->
                        replaceFileContentAndSave(newFilePart, contentLength,document, newStoragePath, oldStoragePath));
    }



    protected Mono<Document> saveDocumentInDatabase(FilePart filePart, Long contentLength, UUID parentFolderId, Map<String, Object> metadata, String originalFilename, String storagePath) {
        if(contentLength == null) {
            return storageService.getFileLength(storagePath)
                    .flatMap(fileLength -> saveDocumentInDB(filePart, storagePath, fileLength, parentFolderId, metadata, originalFilename));
        }
        return saveDocumentInDB(filePart, storagePath, contentLength, parentFolderId, metadata, originalFilename);
    }

    private Mono<Document> saveDocumentInDB(FilePart filePart, String storagePath, Long contentLength, UUID parentFolderId, Map<String, Object> metadata, String originalFilename) {
        Document.DocumentBuilder documentBuilder = Document.builder()
                .name(originalFilename)
                .type(FILE)
                .contentType(filePart.headers().getContentType() != null ? filePart.headers().getContentType().toString() : APPLICATION_OCTET_STREAM)
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



    protected Mono<Document> replaceDocumentInDB(FilePart newFilePart, String newStoragePath, String oldStoragePath, Long contentLength, Document document) {
        return doSaveFile(username -> {
                    document.setStoragePath(newStoragePath);
                    document.setContentType(newFilePart.headers().getContentType() != null ? newFilePart.headers().getContentType().toString() : APPLICATION_OCTET_STREAM);
                    document.setUpdatedAt(OffsetDateTime.now());
                    document.setUpdatedBy(username);
                    document.setSize(contentLength);
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
