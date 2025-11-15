package org.openfilz.dms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.openfilz.dms.dto.audit.UploadAudit;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.ChecksumService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.utils.JsonUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

import static org.openfilz.dms.enums.DocumentType.FILE;

@Service
@ConditionalOnProperty(name = "openfilz.calculate-checksum", havingValue = "true")
public class ChecksumSaveDocumentServiceImpl extends SaveDocumentServiceImpl {
    private final ChecksumService checksumService;

    public ChecksumSaveDocumentServiceImpl(StorageService storageService, ObjectMapper objectMapper, AuditService auditService, JsonUtils jsonUtils, DocumentDAO documentDAO, MetadataPostProcessor metadataPostProcessor, TransactionalOperator tx, ChecksumService checksumService) {
        super(storageService, objectMapper, auditService, jsonUtils, documentDAO, metadataPostProcessor, tx);
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
    protected Mono<Document> replaceFileContentAndSave(FilePart newFilePart, Long contentLength, Document document, String newStoragePath, String oldStoragePath) {
        Json metadata = document.getMetadata();
        return checksumService.calculateChecksum(newStoragePath, metadata != null ? jsonUtils.toMap(metadata) : null)
                .flatMap(checksum -> {
                    document.setMetadata(jsonUtils.toJson(checksum.metadataWithChecksum()));
                    return super.replaceFileContentAndSave(newFilePart, contentLength, document, newStoragePath, oldStoragePath);
                });
    }

}
