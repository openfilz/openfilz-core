package org.openfilz.dms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.ChecksumService;
import org.openfilz.dms.service.PostProcessorService;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.utils.JsonUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "openfilz.calculate-checksum", havingValue = "true")
public class ChecksumSaveDocumentServiceImpl extends SaveDocumentServiceImpl {
    private final ChecksumService checksumService;

    public ChecksumSaveDocumentServiceImpl(StorageService storageService, ObjectMapper objectMapper,
                                           AuditService auditService, JsonUtils jsonUtils, DocumentDAO documentDAO,
                                           ChecksumService checksumService, PostProcessorService postProcessorService) {
        super(storageService, objectMapper, auditService, jsonUtils, documentDAO, postProcessorService);
        this.checksumService = checksumService;
    }

    @Override
    public Mono<UploadResponse> doSaveDocument(FilePart filePart, Long contentLength, UUID parentFolderId, Map<String, Object> metadata, String originalFilename, Authentication auth, Mono<String> storagePathMono) {
        return storagePathMono
                .flatMap(storagePath -> checksumService.calculateChecksum(storagePath, metadata))
                .flatMap(checksum -> saveDocumentInDatabase(filePart, contentLength, parentFolderId, checksum.metadataWithChecksum(), originalFilename, auth, checksum.storagePath()))
                .flatMap(savedDoc -> auditUploadActionAndReturnResponse(parentFolderId, metadata, auth, savedDoc)
                        .doOnSuccess(_ -> postProcessDocument(filePart, savedDoc)));
    }

    @Override
    protected Mono<Document> replaceDocumentContentAndSave(FilePart newFilePart, Long contentLength, Authentication auth, Document document, String newStoragePath, String oldStoragePath) {
        Json metadata = document.getMetadata();
        return checksumService.calculateChecksum(newStoragePath, metadata != null ? jsonUtils.toMap(metadata) : null)
                .flatMap(checksum -> {
                    document.setMetadata(jsonUtils.toJson(checksum.metadataWithChecksum()));
                    return super.replaceDocumentContentAndSave(newFilePart, contentLength, auth, document, newStoragePath, oldStoragePath);
                })
                .doOnSuccess(savedDoc -> postProcessDocument(newFilePart, savedDoc));
    }

}
