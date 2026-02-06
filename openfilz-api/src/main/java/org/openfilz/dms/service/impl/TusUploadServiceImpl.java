package org.openfilz.dms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.QuotaProperties;
import org.openfilz.dms.config.TusProperties;
import org.openfilz.dms.dto.TusUploadMetadata;
import org.openfilz.dms.dto.audit.UploadAudit;
import org.openfilz.dms.dto.request.TusFinalizeRequest;
import org.openfilz.dms.dto.response.TusUploadInfo;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AccessType;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.exception.*;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.TusUploadService;
import org.openfilz.dms.utils.ContentTypeMapper;
import org.openfilz.dms.utils.JsonUtils;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.openfilz.dms.enums.DocumentType.FILE;
import static org.openfilz.dms.enums.DocumentType.FOLDER;

/**
 * Reactive implementation of TusUploadService.
 * Uses StorageService for all storage operations, making it work with both
 * FileSystem and MinIO/S3 backends.
 *
 * Upload data is stored using StorageService:
 * - _tus/{uploadId}.bin - the actual file data (or chunk objects for MinIO)
 * - _tus/{uploadId}.json - metadata (length, offset, expiration, etc.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.tus.enabled", havingValue = "true", matchIfMissing = true)
public class TusUploadServiceImpl implements TusUploadService, UserInfoService {

    private final TusProperties tusProperties;
    private final QuotaProperties quotaProperties;
    private final StorageService storageService;
    private final DocumentDAO documentDAO;
    private final AuditService auditService;
    private final JsonUtils jsonUtils;
    private final MetadataPostProcessor metadataPostProcessor;
    private final TransactionalOperator tx;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> validateUploadCreation(Long uploadLength, String filename, UUID parentFolderId, Boolean allowDuplicateFileNames) {
        String effectiveFilename = filename != null ? filename : "upload";
        return validateFileUploadQuota(uploadLength, effectiveFilename)
                .then(validateUserQuota(uploadLength))
                .then(validateParentFolder(parentFolderId))
                .then(validateDuplicateName(effectiveFilename, parentFolderId, allowDuplicateFileNames));
    }

    @Override
    public Mono<String> createUpload(Long uploadLength, String metadata) {
        String uploadId = UUID.randomUUID().toString();

        // Parse TUS metadata header (base64 encoded key-value pairs)
        Map<String, String> parsedMetadata = parseMetadataHeader(metadata);

        return getConnectedUserEmail().flatMap(email -> {
            // Create metadata record
            TusUploadMetadata uploadMetadata = TusUploadMetadata.create(
                    uploadId,
                    uploadLength,
                    tusProperties.getUploadExpirationPeriod(),
                    parsedMetadata,
                    email
            );

            String dataPath = storageService.getTusDataPath(uploadId);
            String metaPath = storageService.getTusMetadataPath(uploadId);

            log.debug("Creating TUS upload: dataPath={}, metaPath={}", dataPath, metaPath);

            // Create empty data file and write metadata
            return storageService.createEmptyFile(dataPath)
                    .doOnSuccess(v -> log.debug("Created empty data file: {}", dataPath))
                    .doOnError(e -> log.error("Failed to create empty data file: {}", dataPath, e))
                    .then(storageService.saveData(metaPath, serializeToDataBufferFlux(uploadMetadata)))
                    .doOnSuccess(v -> log.info("Created TUS upload: {} (dataPath={}, metaPath={})", uploadId, dataPath, metaPath))
                    .doOnError(e -> log.error("Failed to save TUS metadata: {}", metaPath, e))
                    .thenReturn(uploadId);
        });

    }

    @Override
    public Mono<TusUploadInfo> getUploadInfo(String uploadId, String baseUrl) {
        return loadMetadata(uploadId)
                .map(meta -> TusUploadInfo.of(
                        uploadId,
                        meta.offset(),
                        meta.length(),
                        OffsetDateTime.ofInstant(meta.expiresAt(), java.time.ZoneOffset.UTC),
                        baseUrl
                ));
    }

    @Override
    public Mono<Long> getUploadOffset(String uploadId) {
        return loadMetadata(uploadId)
                .map(TusUploadMetadata::offset);
    }

    @Override
    public Mono<Long> getUploadLength(String uploadId) {
        return loadMetadata(uploadId)
                .map(TusUploadMetadata::length);
    }

    @Override
    public Mono<Long> uploadChunk(String uploadId, Long expectedOffset, Flux<DataBuffer> data) {
        return loadMetadata(uploadId)
                .flatMap(meta -> {
                    // Verify offset matches
                    if (!meta.offset().equals(expectedOffset)) {
                        return Mono.error(new TusUploadException(
                                "Offset mismatch. Expected: " + meta.offset() + ", Got: " + expectedOffset));
                    }

                    // Check if upload is expired
                    if (meta.isExpired()) {
                        return Mono.error(new TusUploadException("Upload has expired"));
                    }

                    String dataPath = storageService.getTusDataPath(uploadId);

                    // Write chunk using StorageService
                    return storageService.appendData(dataPath, data, meta.offset())
                            .flatMap(newOffset -> {
                                // Update metadata with new offset
                                TusUploadMetadata updatedMeta = meta.withOffset(newOffset);
                                return saveMetadata(updatedMeta)
                                        .thenReturn(newOffset);
                            });
                });
    }

    @Override
    public Mono<UploadResponse> finalizeUpload(String uploadId, TusFinalizeRequest request) {
        return loadMetadata(uploadId)
                .flatMap(meta -> {
                    // Verify upload is complete
                    if (!meta.isComplete()) {
                        return Mono.error(new TusUploadException(
                                "Upload is not complete. Offset: " + meta.offset() + ", Expected: " + meta.length()));
                    }

                    String filename = request.filename();
                    UUID parentFolderId = request.parentFolderId();

                    // Validate and create document
                    return validateFileUploadQuota(meta.length(), filename)
                            .then(validateUserQuota(meta.length()))
                            .then(validateParentFolder(parentFolderId))
                            .then(validateDuplicateName(filename, parentFolderId, request.allowDuplicateFileNames()))
                            .then(moveToStorageAndCreateDocument(uploadId, meta, request));
                });
    }

    private Mono<Void> validateFileUploadQuota(Long fileSize, String filename) {
        if (!quotaProperties.isFileUploadQuotaEnabled()) {
            return Mono.empty();
        }
        Long maxSize = quotaProperties.getFileUploadQuotaInBytes();
        if (fileSize > maxSize) {
            return Mono.error(new FileSizeExceededException(filename, fileSize, maxSize));
        }
        return Mono.empty();
    }

    private Mono<Void> validateUserQuota(Long fileSize) {
        if (!quotaProperties.isUserQuotaEnabled()) {
            return Mono.empty();
        }
        Long maxQuota = quotaProperties.getUserQuotaInBytes();
        return getConnectedUserEmail()
                .flatMap(username -> documentDAO.getTotalStorageByUser(username)
                        .flatMap(currentUsage -> {
                            if (currentUsage + fileSize > maxQuota) {
                                return Mono.error(new UserQuotaExceededException(username, currentUsage, fileSize, maxQuota));
                            }
                            return Mono.empty();
                        }));
    }

    private Mono<Void> validateParentFolder(UUID parentFolderId) {
        if (parentFolderId == null) {
            return Mono.empty();
        }
        return documentDAO.existsByIdAndType(parentFolderId, FOLDER, AccessType.RW)
                .flatMap(exists -> {
                    if (!exists) {
                        return Mono.error(new DocumentNotFoundException("Parent folder not found: " + parentFolderId));
                    }
                    return Mono.empty();
                });
    }

    private Mono<Void> validateDuplicateName(String filename, UUID parentFolderId, Boolean allowDuplicates) {
        if (Boolean.TRUE.equals(allowDuplicates)) {
            return Mono.empty();
        }
        return documentDAO.existsByNameAndParentId(filename, parentFolderId)
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new DuplicateNameException(FOLDER, filename));
                    }
                    return Mono.empty();
                });
    }

    private Mono<UploadResponse> moveToStorageAndCreateDocument(String uploadId, TusUploadMetadata meta,
                                                                 TusFinalizeRequest request) {
        String filename = request.filename();
        String storagePath = storageService.getUniqueStorageFileName(filename);
        String tusDataPath = storageService.getTusDataPath(uploadId);

        // Move file from TUS temp location to permanent storage
        return storageService.moveFile(tusDataPath, storagePath)
                .then(createDocumentRecord(storagePath, meta, request, uploadId));
    }

    private Mono<UploadResponse> createDocumentRecord(String storagePath, TusUploadMetadata meta,
                                                       TusFinalizeRequest request, String uploadId) {
        String filename = request.filename();
        String contentType = getContentType(filename);

        return getConnectedUserEmail()
                .flatMap(username -> {
                    Document document = Document.builder()
                            .name(filename)
                            .type(FILE)
                            .contentType(contentType)
                            .size(meta.length())
                            .parentId(request.parentFolderId())
                            .storagePath(storagePath)
                            .metadata(jsonUtils.toJson(request.metadata()))
                            .createdAt(OffsetDateTime.now())
                            .updatedAt(OffsetDateTime.now())
                            .createdBy(username)
                            .updatedBy(username)
                            .build();

                    return documentDAO.create(document)
                            .flatMap(savedDoc -> auditService.logAction(
                                    AuditAction.UPLOAD_DOCUMENT,
                                    FILE,
                                    savedDoc.getId(),
                                    new UploadAudit(savedDoc.getName(), request.parentFolderId(), request.metadata())
                            ).thenReturn(savedDoc))
                            .as(tx::transactional)
                            .doOnSuccess(this::postProcessDocument)
                            .doOnSuccess(doc -> cleanupTusMetadata(uploadId).subscribe())
                            .map(savedDoc -> new UploadResponse(
                                    savedDoc.getId(),
                                    savedDoc.getName(),
                                    savedDoc.getContentType(),
                                    savedDoc.getSize()
                            ));
                });
    }

    private void postProcessDocument(Document document) {
        metadataPostProcessor.processDocument(document);
    }

    private Mono<Void> cleanupTusMetadata(String uploadId) {
        String metaPath = storageService.getTusMetadataPath(uploadId);
        return storageService.deleteFile(metaPath)
                .doOnSuccess(v -> log.debug("Cleaned up TUS metadata: {}", uploadId))
                .onErrorResume(e -> {
                    log.warn("Error cleaning up TUS metadata {}: {}", uploadId, e.getMessage());
                    return Mono.empty();
                });
    }

    private String getContentType(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < filename.length() - 1) {
            String extension = filename.substring(dotIndex + 1);
            String contentType = ContentTypeMapper.getContentType(extension);
            if (contentType != null) {
                return contentType;
            }
        }
        return "application/octet-stream";
    }

    @Override
    public Mono<Void> cancelUpload(String uploadId) {
        String dataPath = storageService.getTusDataPath(uploadId);
        String metaPath = storageService.getTusMetadataPath(uploadId);

        // Delete both data and metadata files
        // For MinIO, we also need to clean up chunk objects
        return storageService.listFiles(dataPath + ".chunk.")
                .flatMap(chunkPath -> storageService.deleteFile(chunkPath))
                .then(storageService.deleteFile(dataPath))
                .then(storageService.deleteFile(metaPath))
                .doOnSuccess(v -> log.debug("Cancelled TUS upload: {}", uploadId))
                .onErrorResume(e -> {
                    log.warn("Error cancelling TUS upload {}: {}", uploadId, e.getMessage());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Boolean> isUploadComplete(String uploadId) {
        return loadMetadata(uploadId)
                .map(TusUploadMetadata::isComplete)
                .onErrorReturn(false);
    }

    @Override
    public Mono<Integer> cleanupExpiredUploads() {
        String tusPrefix = StorageService.TUS_PREFIX;

        return storageService.listFiles(tusPrefix)
                .filter(path -> path.endsWith(".json"))
                .flatMap(metaPath -> {
                    String uploadId = extractUploadIdFromMetaPath(metaPath);
                    return loadMetadata(false, uploadId)
                            .filter(TusUploadMetadata::isExpired)
                            .flatMap(meta -> cancelUpload(uploadId).thenReturn(1))
                            .onErrorResume(e -> {
                                log.warn("Error checking/cleaning expired upload {}: {}", uploadId, e.getMessage());
                                return Mono.just(0);
                            });
                })
                .reduce(0, Integer::sum)
                .doOnSuccess(count -> {
                    if (count > 0) {
                        log.info("Cleaned up {} expired TUS uploads", count);
                    }
                });
    }

    private String extractUploadIdFromMetaPath(String metaPath) {
        // metaPath is like "_tus/{uploadId}.json"
        String filename = metaPath.substring(metaPath.lastIndexOf('/') + 1);
        return filename.replace(".json", "");
    }

    private Mono<TusUploadMetadata> loadMetadata(String uploadId) {
        return loadMetadata(true, uploadId);
    }

    private Mono<TusUploadMetadata> loadMetadata(boolean checkOwner, String uploadId) {
        return getConnectedUserEmail()
                .flatMap(email -> {
                    String metaPath = storageService.getTusMetadataPath(uploadId);
                    log.debug("Loading TUS metadata: uploadId={}, metaPath={}", uploadId, metaPath);
                    return storageService.loadFile(metaPath)
                            .doOnSuccess(r -> log.debug("loadFile succeeded for {}, resource={}", metaPath, r))
                            .doOnError(e -> log.error("Failed to load TUS metadata file: {} - {}", metaPath, e.getMessage(), e))
                            .flatMap(resource -> Mono.fromCallable(() -> {
                                log.debug("Reading InputStream from resource: {}", resource);
                                try (InputStream is = resource.getInputStream()) {
                                    TusUploadMetadata meta = objectMapper.readValue(is, TusUploadMetadata.class);
                                    if(checkOwner && !email.equals(meta.email())) {
                                        throw new OperationForbiddenException("Upload email does not match TUS email");
                                    }
                                    log.debug("Loaded TUS metadata: uploadId={}, offset={}, length={}", uploadId, meta.offset(), meta.length());
                                    return meta;
                                } catch (Exception e) {
                                    log.error("Error reading/parsing TUS metadata from {}: {}", metaPath, e.getMessage(), e);
                                    throw e;
                                }
                            }).subscribeOn(Schedulers.boundedElastic()))
                            .onErrorMap(e -> {
                                if (e instanceof TusUploadException) {
                                    return e;
                                }
                                log.warn("Upload not found: {} (path={}) - error: {}", uploadId, metaPath, e.getMessage(), e);
                                return new DocumentNotFoundException("Upload not found: " + uploadId);
                            });
                });
    }

    private Mono<Void> saveMetadata(TusUploadMetadata metadata) {
        String metaPath = storageService.getTusMetadataPath(metadata.uploadId());
        return storageService.saveData(metaPath, serializeToDataBufferFlux(metadata))
                .onErrorMap(e -> new TusUploadException("Error saving upload metadata", e));
    }

    /**
     * Serialize an object to JSON and wrap in a Flux of DataBuffer.
     * Uses Jackson's streaming to write directly to a DataBuffer.
     */
    private <T> Flux<DataBuffer> serializeToDataBufferFlux(T object) {
        return Flux.defer(() -> {
            try {
                // Use Jackson to serialize to bytes, then wrap in DataBuffer
                // For small metadata objects, this is efficient
                byte[] jsonBytes = objectMapper.writeValueAsBytes(object);
                DataBuffer buffer = new DefaultDataBufferFactory().wrap(jsonBytes);
                return Flux.just(buffer);
            } catch (IOException e) {
                return Flux.error(new TusUploadException("Error serializing metadata to JSON", e));
            }
        });
    }

    /**
     * Parse TUS Upload-Metadata header.
     * Format: key1 base64value1,key2 base64value2,...
     */
    private Map<String, String> parseMetadataHeader(String header) {
        Map<String, String> result = new HashMap<>();
        if (header == null || header.isBlank()) {
            return result;
        }

        String[] pairs = header.split(",");
        for (String pair : pairs) {
            String[] parts = pair.trim().split(" ", 2);
            if (parts.length == 2) {
                String key = parts[0].trim();
                try {
                    String value = new String(Base64.getDecoder().decode(parts[1].trim()));
                    result.put(key, value);
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid base64 in TUS metadata: {}", pair);
                }
            } else if (parts.length == 1) {
                // Key without value
                result.put(parts[0].trim(), "");
            }
        }
        return result;
    }
}
