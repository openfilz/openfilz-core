package org.openfilz.dms.service.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.service.quota.StorageQuotaService;
import org.openfilz.dms.config.TusProperties;
import org.openfilz.dms.dto.Checksum;
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
import org.openfilz.dms.service.ChecksumService;
import org.openfilz.dms.service.DocumentIntegrityService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.TusUploadService;
import org.openfilz.dms.utils.ContentTypeMapper;
import org.openfilz.dms.utils.JsonUtils;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final StorageQuotaService storageQuotaService;
    private final StorageService storageService;
    private final DocumentDAO documentDAO;
    private final AuditService auditService;
    private final JsonUtils jsonUtils;
    private final MetadataPostProcessor metadataPostProcessor;
    private final TransactionalOperator tx;
    private final ObjectMapper objectMapper;
    /**
     * With checksums on, a finished upload is fingerprinted and entered in the integrity ledger (C2) like
     * every other upload path — without it a large file sent in pieces had no SHA-256, which the desktop
     * sync uses as the content's identity (ETag / If-Match).
     */
    private final ObjectProvider<ChecksumService> checksumServiceProvider;
    private final DocumentIntegrityService documentIntegrityService;

    @Value("${openfilz.calculate-checksum:false}")
    private boolean calculateChecksum;

    /**
     * Uploads being finalized on this instance. A second finalize of the same upload (a client that
     * gave up waiting and asks again) must not run beside the first — both would create a document on
     * the same stored file — and the clean-up must not take the file away from under it.
     */
    private final Set<String> finalizing = ConcurrentHashMap.newKeySet();

    @Override
    public Mono<Void> validateUploadCreation(Long uploadLength, String filename, UUID parentFolderId, Boolean allowDuplicateFileNames) {
        String effectiveFilename = filename != null ? filename : "upload";
        return storageQuotaService.checkUpload(effectiveFilename, uploadLength)
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

    /**
     * An unfinished upload past its expiration is gone (410): a client that asks where to resume is
     * told to start again, at once, instead of sending pieces that will be refused. A complete one
     * still answers — it only waits for its finalize, which may be asked again.
     */
    @Override
    public Mono<Long> getUploadOffset(String uploadId) {
        return loadMetadata(uploadId)
                .flatMap(meta -> meta.isExpired() && !meta.isComplete()
                        ? Mono.<Long>error(new TusUploadExpiredException(uploadId))
                        : Mono.just(meta.offset()));
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
                    // An expired upload takes nothing more, whatever the offset says
                    if (meta.isExpired()) {
                        return Mono.error(new TusUploadExpiredException(uploadId));
                    }

                    // Verify offset matches
                    if (!meta.offset().equals(expectedOffset)) {
                        return Mono.error(new TusUploadException(
                                "Offset mismatch. Expected: " + meta.offset() + ", Got: " + expectedOffset));
                    }

                    String dataPath = storageService.getTusDataPath(uploadId);

                    // Never accept a byte past the declared Upload-Length: that length is what the
                    // quotas were checked against at creation. The stream fails as soon as it would
                    // overflow, before the offset is recorded, so the extra bytes are never counted.
                    long remaining = meta.length() - meta.offset();
                    Flux<DataBuffer> bounded = limitTo(data, remaining, meta.length());

                    // Write chunk using StorageService
                    return storageService.appendData(dataPath, bounded, meta.offset())
                            .flatMap(newOffset -> {
                                // Update metadata with new offset
                                TusUploadMetadata updatedMeta = meta.withOffset(newOffset);
                                return saveMetadata(updatedMeta)
                                        .thenReturn(newOffset);
                            });
                });
    }

    /** Passes {@code data} through, failing once more than {@code remaining} bytes went by. */
    static Flux<DataBuffer> limitTo(Flux<DataBuffer> data, long remaining, long uploadLength) {
        java.util.concurrent.atomic.AtomicLong seen = new java.util.concurrent.atomic.AtomicLong();
        return data.handle((buffer, sink) -> {
            if (seen.addAndGet(buffer.readableByteCount()) > remaining) {
                org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
                sink.error(new TusUploadLengthExceededException(uploadLength));
            } else {
                sink.next(buffer);
            }
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

                    if (!finalizing.add(uploadId)) {
                        // Not a refusal: the caller asks again later and finds the upload finalized.
                        return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                                "Upload " + uploadId + " is already being finalized"));
                    }
                    // Validate and create document. Deferred as a whole: whatever fails, however it fails,
                    // the upload leaves the set — left in it, it could never be finalized nor cleaned up.
                    return Mono.defer(() -> storageQuotaService.checkUpload(filename, meta.length())
                                    .then(validateParentFolder(parentFolderId))
                                    .then(validateDuplicateName(filename, parentFolderId, request.allowDuplicateFileNames()))
                                    .then(Mono.defer(() -> moveToStorageAndCreateDocument(uploadId, meta, request))))
                            .doFinally(_ -> finalizing.remove(uploadId));
                });
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
        return moveToStorage(uploadId, meta, request.filename())
                .flatMap(storagePath -> createDocumentRecord(storagePath, meta, request, uploadId));
    }

    /**
     * Moves the finished upload to its permanent place — once. Where it goes is written in the
     * upload's metadata before the move, so a finalize cut after it (a proxy's timeout, a restart,
     * while the file was being hashed) is finished by the next one from the file already there: moving
     * again would find nothing left to move — on S3 the pieces are gone once composed, and an empty
     * object would stand for the file. Whatever the storage did, a document is only ever created on a
     * stored file of the announced length.
     */
    private Mono<String> moveToStorage(String uploadId, TusUploadMetadata meta, String filename) {
        String tusDataPath = storageService.getTusDataPath(uploadId);
        String remembered = meta.finalStoragePath();
        if (remembered != null) {
            return documentDAO.existsByStoragePath(remembered).flatMap(held -> held
                    // The earlier finalize went all the way (only its clean-up is late): no second document on its file.
                    ? Mono.<String>error(new DocumentNotFoundException("Upload not found: " + uploadId))
                    : storedLength(remembered).flatMap(length -> length.equals(meta.length())
                            ? Mono.just(remembered) // an earlier finalize moved it and went no further
                            : moveAndVerify(tusDataPath, remembered, meta))); // it stopped before the move
        }
        String storagePath = storageService.getUniqueStorageFileName(filename);
        return saveMetadata(meta.withFinalStoragePath(storagePath))
                .then(Mono.defer(() -> moveAndVerify(tusDataPath, storagePath, meta)));
    }

    private Mono<String> moveAndVerify(String tusDataPath, String storagePath, TusUploadMetadata meta) {
        return storageService.moveFile(tusDataPath, storagePath)
                .then(Mono.defer(() -> storedLength(storagePath)))
                .flatMap(length -> {
                    if (length.equals(meta.length())) {
                        return Mono.just(storagePath);
                    }
                    log.error("TUS upload {}: {} bytes stored at {} for {} announced — no document created",
                            meta.uploadId(), length, storagePath, meta.length());
                    // 410: what was sent is no longer all there, the upload has to start again.
                    return storageService.deleteFile(storagePath)
                            .onErrorResume(e -> Mono.empty())
                            .then(Mono.error(new ResponseStatusException(HttpStatus.GONE,
                                    "The data of upload " + meta.uploadId() + " is no longer complete on the server: send the file again")));
                });
    }

    /**
     * The length of what is stored at this path; -1 when nothing is. A storage that cannot say (down,
     * timing out) is an error, never "nothing": on that guess the file already moved would be moved
     * again — an empty object written over it on S3 — or deleted as the wrong length. The finalize
     * fails and is asked again.
     */
    private Mono<Long> storedLength(String storagePath) {
        return Mono.defer(() -> storageService.getFileLength(storagePath))
                .onErrorResume(TusUploadServiceImpl::isNotFound, e -> Mono.just(-1L));
    }

    /** The storage said there is no such file (local: no such path; S3: NoSuchKey) — as opposed to failing to answer. */
    static boolean isNotFound(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
            if (cause instanceof java.nio.file.NoSuchFileException || cause instanceof java.io.FileNotFoundException) {
                return true;
            }
            if (cause instanceof io.minio.errors.ErrorResponseException s3 && s3.errorResponse() != null
                    && "NoSuchKey".equals(s3.errorResponse().code())) {
                return true;
            }
        }
        return false;
    }

    private Mono<UploadResponse> createDocumentRecord(String storagePath, TusUploadMetadata meta,
                                                       TusFinalizeRequest request, String uploadId) {
        String filename = request.filename();
        String contentType = getContentType(filename);

        return Mono.zip(getConnectedUserEmail(), checksum(storagePath, request.metadata()))
                .flatMap(userAndChecksum -> {
                    String username = userAndChecksum.getT1();
                    Optional<Checksum> checksum = userAndChecksum.getT2();
                    Document document = Document.builder()
                            .name(filename)
                            .type(FILE)
                            .contentType(contentType)
                            .size(meta.length())
                            .parentId(request.parentFolderId())
                            .storagePath(checksum.map(Checksum::storagePath).orElse(storagePath))
                            .metadata(jsonUtils.toJson(checksum.map(Checksum::metadataWithChecksum).orElse(request.metadata())))
                            .createdAt(OffsetDateTime.now())
                            .updatedAt(OffsetDateTime.now())
                            .createdBy(username)
                            .updatedBy(username)
                            .build();

                    return documentDAO.create(document)
                            // The ledger entry lands in the same transaction as the document row.
                            .flatMap(savedDoc -> checksum
                                    .map(c -> documentIntegrityService.record(savedDoc.getId(), savedDoc.getStoragePath(), null, c.hash())
                                            .thenReturn(savedDoc))
                                    .orElseGet(() -> Mono.just(savedDoc)))
                            .flatMap(savedDoc -> storageService.getLatestVersionId(savedDoc.getStoragePath())
                                    .map(versionId -> new UploadAudit(savedDoc.getName(), request.parentFolderId(), request.metadata(), versionId))
                                    .defaultIfEmpty(new UploadAudit(savedDoc.getName(), request.parentFolderId(), request.metadata()))
                                    .flatMap(details -> auditService.logAction(AuditAction.UPLOAD_DOCUMENT, FILE, savedDoc.getId(), details))
                                    .thenReturn(savedDoc))
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

    /** The finished file's fingerprint when checksums are on; empty otherwise. */
    private Mono<Optional<Checksum>> checksum(String storagePath, Map<String, Object> metadata) {
        ChecksumService checksumService = calculateChecksum ? checksumServiceProvider.getIfAvailable() : null;
        if (checksumService == null) {
            return Mono.just(Optional.empty());
        }
        return checksumService.calculateChecksum(storagePath, metadata).map(Optional::of);
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

    /**
     * Only its owner cancels an upload. One the server does not know — or that is someone else's,
     * which reads the same from outside — is left alone: there is nothing of the caller's to remove.
     */
    @Override
    public Mono<Void> cancelUpload(String uploadId) {
        return loadMetadata(uploadId)
                .onErrorResume(DocumentNotFoundException.class, e -> Mono.empty())
                .flatMap(meta -> removeUpload(uploadId, meta));
    }

    private Mono<Void> removeUpload(String uploadId, TusUploadMetadata meta) {
        if (finalizing.contains(uploadId)) {
            return Mono.empty(); // being turned into a document right now: not the moment
        }
        String dataPath = storageService.getTusDataPath(uploadId);
        String metaPath = storageService.getTusMetadataPath(uploadId);

        // Delete both data and metadata files
        // For MinIO, we also need to clean up chunk objects
        return storageService.listFiles(dataPath + ".chunk.")
                .flatMap(chunkPath -> storageService.deleteFile(chunkPath))
                .then(storageService.deleteFile(dataPath))
                .then(removeMovedFile(meta))
                // Last: while the metadata is there, a step that failed above is tried again by the next clean-up.
                .then(storageService.deleteFile(metaPath))
                .doOnSuccess(v -> log.debug("Cancelled TUS upload: {}", uploadId))
                .onErrorResume(e -> {
                    log.warn("Error cancelling TUS upload {}: {}", uploadId, e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * What an interrupted finalize moved to permanent storage and no document holds would stay there
     * for ever, counted in nobody's quota: it goes with the upload. A file a document holds (the
     * finalize went through, only its own clean-up did not) is never touched.
     */
    private Mono<Void> removeMovedFile(TusUploadMetadata meta) {
        String moved = meta.finalStoragePath();
        if (moved == null) {
            return Mono.empty();
        }
        return documentDAO.existsByStoragePath(moved)
                .flatMap(held -> held ? Mono.<Void>empty() : storageService.deleteFile(moved));
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
                            .flatMap(meta -> removeUpload(uploadId, meta).thenReturn(1))
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
                            .flatMap(resource -> Mono.<TusUploadMetadata>fromCallable(() -> {
                                log.debug("Reading InputStream from resource: {}", resource);
                                try (BufferedInputStream bis = new BufferedInputStream(resource.getInputStream(), 1)) {
                                    bis.mark(1);
                                    if (bis.read() == -1) {
                                        log.warn("TUS metadata file is empty (interrupted upload?): {}", metaPath);
                                        throw new DocumentNotFoundException("Upload metadata is empty: " + uploadId);
                                    }
                                    bis.reset();
                                    TusUploadMetadata meta = objectMapper.readValue(bis, TusUploadMetadata.class);
                                    if(checkOwner && !email.equals(meta.email())) {
                                        throw new OperationForbiddenException("Upload email does not match TUS email");
                                    }
                                    log.debug("Loaded TUS metadata: uploadId={}, offset={}, length={}", uploadId, meta.offset(), meta.length());
                                    return meta;
                                } catch (DocumentNotFoundException | OperationForbiddenException e) {
                                    throw e;
                                } catch (Exception e) {
                                    log.warn("Corrupted TUS metadata file {}: {}", metaPath, e.getMessage());
                                    throw new DocumentNotFoundException("Upload metadata is corrupted: " + uploadId);
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
            } catch (JacksonException e) {
                // Jackson 3 throws unchecked JacksonException instead of IOException
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
                    String value = new String(Base64.getDecoder().decode(parts[1].trim()), StandardCharsets.UTF_8);
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
