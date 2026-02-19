// com/example/dms/service/impl/MinioStorageService.java
package org.openfilz.dms.service.impl;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import io.minio.messages.ObjectLockConfiguration;
import io.minio.messages.VersioningConfiguration;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.MinioProperties;
import org.openfilz.dms.exception.StorageException;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.utils.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE;

@Slf4j
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "minio")
public class MinioStorageService implements StorageService {

    private final MinioProperties minioProperties;

    private MinioClient minioClient;

    @Value("${piped.buffer.size:8192}")
    private Integer pipedBufferSize;

    @Value("${openfilz.security.worm-mode:false}")
    private Boolean wormMode;

    public MinioStorageService(MinioProperties minioProperties) {
        this.minioProperties = minioProperties;
    }

    @PostConstruct
    public void init() {
        this.minioClient = MinioClient.builder()
                .endpoint(minioProperties.getEndpoint())
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .build();
        ensureBucketExists();
    }

    private void ensureBucketExists() {
        try {
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(minioProperties.getBucketName()).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(minioProperties.getBucketName()).objectLock(wormMode).build());
                if(minioProperties.isVersioningEnabled()) {
                    minioClient.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(minioProperties.getBucketName()).config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED, false)).build());
                }
                log.info("Bucket '{}' created successfully.", minioProperties.getBucketName());
            } else {
                log.info("Bucket '{}' already exists.", minioProperties.getBucketName());
                if(minioProperties.isVersioningEnabled()) {
                    VersioningConfiguration bucketVersioning = minioClient.getBucketVersioning(GetBucketVersioningArgs.builder().bucket(minioProperties.getBucketName()).build());
                    log.debug("Bucket Versioning: {}", bucketVersioning.status());
                }
                if(wormMode) {
                    try {
                        ObjectLockConfiguration objectLockConfiguration = minioClient.getObjectLockConfiguration(GetObjectLockConfigurationArgs.builder().bucket(minioProperties.getBucketName()).build());
                        log.debug("Object Lock configuration: {}", objectLockConfiguration);
                    } catch (Exception e) {
                        log.error("Object Lock configuration is mandatory when WORM configuration is active (openfilz.security.worm-mode=true)", e);
                        throw new RuntimeException(e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error ensuring bucket '{}' exists", minioProperties.getBucketName(), e);
            System.exit(-1);
        }
    }

    @Override
    public Mono<String> replaceFile(String oldStoragePath, FilePart newFilePart) {
        if (minioProperties.isVersioningEnabled()) {
            // With bucket versioning enabled, overwrite the same object.
            // MinIO automatically keeps the previous version.
            return uploadToObject(oldStoragePath, newFilePart);
        }
        return saveFile(newFilePart);
    }

    @Override
    public Mono<String> saveFile(FilePart filePart) {
        String objectName = getUniqueStorageFileName(filePart.filename());
        return uploadToObject(objectName, filePart);
    }

    /**
     * Uploads a FilePart to MinIO using piped streams, storing it under the given objectName.
     */
    private Mono<String> uploadToObject(String objectName, FilePart filePart) {
        PipedInputStream pipedInputStream = new PipedInputStream(pipedBufferSize);
        PipedOutputStream pipedOutputStream;
        try {
            pipedOutputStream = new PipedOutputStream(pipedInputStream);
        } catch (IOException e) {
            log.error("Failed to create PipedOutputStream", e);
            return Mono.error(e);
        }

        DataBufferUtils.write(filePart.content(), pipedOutputStream)
                .doOnError(e -> {
                    log.error("Error writing file content to PipedOutputStream for {}", objectName, e);
                    try {
                        pipedOutputStream.close();
                    } catch (IOException ignored) {
                    }
                })
                .doOnComplete(() -> {
                    log.debug("Finished writing file content to PipedOutputStream for {}", objectName);
                    try {
                        pipedOutputStream.close();
                    } catch (IOException e) {
                        log.warn("Error closing PipedOutputStream for {}: {}", objectName, e.getMessage());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        return Mono.fromCallable(() -> {
                    log.info("Starting MinIO putObject for {}", objectName);
                    String contentType = FileUtils.getContentType(filePart);
                    PutObjectArgs args = PutObjectArgs.builder()
                            .bucket(minioProperties.getBucketName())
                            .object(objectName)
                            .legalHold(wormMode)
                            .stream(pipedInputStream, -1, PutObjectArgs.MIN_MULTIPART_SIZE)
                            .contentType(contentType != null ? contentType : APPLICATION_OCTET_STREAM_VALUE)
                            .build();
                    minioClient.putObject(args);
                    log.info("Successfully uploaded {} to MinIO bucket {}", objectName, minioProperties.getBucketName());
                    return objectName;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnTerminate(() -> {
                    try {
                        pipedInputStream.close();
                    } catch (IOException e) {
                        log.warn("Error closing PipedInputStream for {}: {}", objectName, e.getMessage());
                    }
                })
                .doOnError(e -> log.error("Failed to upload {} to MinIO", objectName, e));
    }

    @Override
    public Mono<? extends Resource> loadFile(String storagePath) { // storagePath is objectName
        return Mono.fromCallable(() -> {
            try {
                InputStream stream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket(minioProperties.getBucketName())
                                .object(storagePath)
                                .build()
                );
                // InputStreamResource will close the stream when the resource is consumed
                return new InputStreamResource(stream);
            } catch (Exception e) {
                log.error("Error loading file {} from MinIO", storagePath, e);
                throw new RuntimeException("MinIO load file failed for " + storagePath, e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> deleteFile(String storagePath) { // storagePath is objectName
        return Mono.fromRunnable(() -> {
            try {
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket(minioProperties.getBucketName())
                                .object(storagePath)
                                .build()
                );
                log.info("File '{}' deleted successfully from MinIO bucket '{}'", storagePath, minioProperties.getBucketName());
            } catch (Exception e) {
                log.error("Error deleting file {} from MinIO", storagePath, e);
                // Don't throw if object not found, treat as success
                if (!(e instanceof ErrorResponseException && ((ErrorResponseException) e).errorResponse().code().equals("NoSuchKey"))) {
                    throw new RuntimeException("MinIO delete file failed for " + storagePath, e);
                }
                log.warn("File {} not found in MinIO for deletion, presumed already deleted.", storagePath);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<String> copyFile(String sourceStoragePath) {
        String destinationObjectName = getUniqueStorageFileName(getOriginalFileName(sourceStoragePath));
        return Mono.fromCallable(() -> {
            try {
                minioClient.copyObject(
                        CopyObjectArgs.builder()
                                .bucket(minioProperties.getBucketName())
                                .object(destinationObjectName)
                                .legalHold(wormMode)
                                .source(CopySource.builder().bucket(minioProperties.getBucketName()).object(sourceStoragePath).build())
                                .build());
                log.info("File copied from {} to {} in MinIO bucket '{}'", sourceStoragePath, destinationObjectName, minioProperties.getBucketName());
                return destinationObjectName;
            } catch (Exception e) {
                log.error("Error copying file {} to {} in MinIO", sourceStoragePath, destinationObjectName, e);
                throw new RuntimeException("MinIO copy file failed", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Long> getFileLength(String storagePath) {
        return Mono.fromCallable(() -> {
            try {
                StatObjectArgs statObjectArgs = StatObjectArgs.builder()
                        .bucket(minioProperties.getBucketName())
                        .object(storagePath)
                        .build();

                StatObjectResponse response = minioClient.statObject(statObjectArgs);
                return response.size();
            } catch (Exception e) {
                log.error("Error to get File Length {} in MinIO", storagePath, e);
                throw new StorageException("MinIO getFileLength failed", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ==================== TUS Upload Support Methods ====================
    // For MinIO/S3, we store each chunk as a separate object and compose them at finalization.
    // Chunk objects are stored as: {storagePath}.chunk.{offset}
    // This approach works because S3 doesn't support random writes.

    @Override
    public Mono<Void> createEmptyFile(String storagePath) {
        // For MinIO, we don't need to create an empty file.
        // We'll create chunk objects as data arrives.
        // Just verify the path is valid (no-op for now).
        return Mono.empty();
    }

    @Override
    public Mono<Long> appendData(String storagePath, Flux<DataBuffer> data, long offset) {
        // Store this chunk as a separate object with offset in the name
        String chunkObjectName = storagePath + ".chunk." + String.format("%020d", offset);

        // Use PipedInputStream/PipedOutputStream for streaming without loading all data into memory
        PipedInputStream pipedInputStream = new PipedInputStream(pipedBufferSize);
        PipedOutputStream pipedOutputStream;
        try {
            pipedOutputStream = new PipedOutputStream(pipedInputStream);
        } catch (IOException e) {
            log.error("Failed to create PipedOutputStream for TUS chunk", e);
            return Mono.error(e);
        }

        // Track bytes written
        AtomicLong bytesWritten = new AtomicLong(0);

        // Write data from Flux to PipedOutputStream (runs in separate thread)
        DataBufferUtils.write(data, pipedOutputStream)
                .doOnNext(DataBufferUtils::release)
                .doOnError(e -> {
                    log.error("Error writing TUS chunk to PipedOutputStream for {}", chunkObjectName, e);
                    try {
                        pipedOutputStream.close();
                    } catch (IOException ignored) {
                    }
                })
                .doOnComplete(() -> {
                    log.debug("Finished writing TUS chunk to PipedOutputStream for {}", chunkObjectName);
                    try {
                        pipedOutputStream.close();
                    } catch (IOException e) {
                        log.warn("Error closing PipedOutputStream for {}: {}", chunkObjectName, e.getMessage());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(dataBuffer -> bytesWritten.addAndGet(dataBuffer.readableByteCount()));

        // Upload to MinIO using streaming
        return Mono.fromCallable(() -> {
                    log.debug("Starting MinIO putObject for TUS chunk {}", chunkObjectName);
                    PutObjectArgs args = PutObjectArgs.builder()
                            .bucket(minioProperties.getBucketName())
                            .object(chunkObjectName)
                            .stream(pipedInputStream, -1, PutObjectArgs.MIN_MULTIPART_SIZE)
                            .contentType(APPLICATION_OCTET_STREAM_VALUE)
                            .build();
                    minioClient.putObject(args);

                    // Get the actual size from MinIO after upload
                    StatObjectResponse stat = minioClient.statObject(
                            StatObjectArgs.builder()
                                    .bucket(minioProperties.getBucketName())
                                    .object(chunkObjectName)
                                    .build());
                    long chunkSize = stat.size();
                    log.debug("Stored TUS chunk {} ({} bytes) to MinIO", chunkObjectName, chunkSize);
                    return offset + chunkSize;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnTerminate(() -> {
                    try {
                        pipedInputStream.close();
                    } catch (IOException e) {
                        log.warn("Error closing PipedInputStream for {}: {}", chunkObjectName, e.getMessage());
                    }
                })
                .doOnError(e -> log.error("Failed to store TUS chunk {} to MinIO", chunkObjectName, e));
    }

    @Override
    public Mono<Void> saveData(String storagePath, Flux<DataBuffer> data) {
        // Use PipedInputStream/PipedOutputStream for streaming without loading all data into memory
        PipedInputStream pipedInputStream = new PipedInputStream(pipedBufferSize);
        PipedOutputStream pipedOutputStream;
        try {
            pipedOutputStream = new PipedOutputStream(pipedInputStream);
        } catch (IOException e) {
            log.error("Failed to create PipedOutputStream for saveData", e);
            return Mono.error(e);
        }

        // Write data from Flux to PipedOutputStream (runs in separate thread)
        DataBufferUtils.write(data, pipedOutputStream)
                .doOnNext(DataBufferUtils::release)
                .doOnError(e -> {
                    log.error("Error writing data to PipedOutputStream for {}", storagePath, e);
                    try {
                        pipedOutputStream.close();
                    } catch (IOException ignored) {
                    }
                })
                .doOnComplete(() -> {
                    log.debug("Finished writing data to PipedOutputStream for {}", storagePath);
                    try {
                        pipedOutputStream.close();
                    } catch (IOException e) {
                        log.warn("Error closing PipedOutputStream for {}: {}", storagePath, e.getMessage());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        // Upload to MinIO using streaming
        return Mono.<Void>fromRunnable(() -> {
                    try {
                        log.debug("Starting MinIO putObject for {}", storagePath);
                        PutObjectArgs args = PutObjectArgs.builder()
                                .bucket(minioProperties.getBucketName())
                                .object(storagePath)
                                .stream(pipedInputStream, -1, PutObjectArgs.MIN_MULTIPART_SIZE)
                                .contentType(APPLICATION_OCTET_STREAM_VALUE)
                                .build();
                        minioClient.putObject(args);
                        log.debug("Saved data to MinIO: {}", storagePath);
                    } catch (Exception e) {
                        log.error("Error saving data to MinIO: {}", storagePath, e);
                        throw new StorageException("MinIO saveData failed", e);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnTerminate(() -> {
                    try {
                        pipedInputStream.close();
                    } catch (IOException e) {
                        log.warn("Error closing PipedInputStream for {}: {}", storagePath, e.getMessage());
                    }
                });
    }

    @Override
    public Mono<Void> moveFile(String sourcePath, String destPath) {
        // For TUS finalization, sourcePath will be like "_tus/{uploadId}.bin"
        // We need to compose all chunk objects into the destination
        String chunkPrefix = sourcePath + ".chunk.";

        return Mono.fromCallable(() -> {
            try {
                // List all chunk objects for this upload
                List<String> chunkObjects = new ArrayList<>();
                Iterable<Result<Item>> results = minioClient.listObjects(
                        ListObjectsArgs.builder()
                                .bucket(minioProperties.getBucketName())
                                .prefix(chunkPrefix)
                                .build());

                for (Result<Item> result : results) {
                    chunkObjects.add(result.get().objectName());
                }

                if (chunkObjects.isEmpty()) {
                    // No chunks - this might be an empty file or error
                    // Create an empty object at destination
                    try (ByteArrayInputStream emptyStream = new ByteArrayInputStream(new byte[0])) {
                        minioClient.putObject(PutObjectArgs.builder()
                                .bucket(minioProperties.getBucketName())
                                .object(destPath)
                                .legalHold(wormMode)
                                .stream(emptyStream, 0, -1)
                                .build());
                    }
                    log.info("Created empty file at {} in MinIO", destPath);
                    return null;
                }

                // Sort chunks by offset (extracted from object name)
                chunkObjects.sort(Comparator.naturalOrder());

                // Compose all chunks into the destination object
                List<ComposeSource> sources = chunkObjects.stream()
                        .map(obj -> ComposeSource.builder()
                                .bucket(minioProperties.getBucketName())
                                .object(obj)
                                .build())
                        .toList();

                minioClient.composeObject(ComposeObjectArgs.builder()
                        .bucket(minioProperties.getBucketName())
                        .object(destPath)
                        .sources(sources)
                        .build());

                log.info("Composed {} chunks into {} in MinIO", chunkObjects.size(), destPath);

                // Delete chunk objects
                for (String chunkObject : chunkObjects) {
                    try {
                        minioClient.removeObject(RemoveObjectArgs.builder()
                                .bucket(minioProperties.getBucketName())
                                .object(chunkObject)
                                .build());
                    } catch (Exception e) {
                        log.warn("Error deleting chunk object {}: {}", chunkObject, e.getMessage());
                    }
                }

                return null;
            } catch (Exception e) {
                log.error("Error moving file from {} to {} in MinIO", sourcePath, destPath, e);
                throw new StorageException("MinIO moveFile failed", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Void> deleteLatestVersion(String storagePath) {
        if (!minioProperties.isVersioningEnabled()) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> {
            try {
                StatObjectResponse stat = minioClient.statObject(
                        StatObjectArgs.builder()
                                .bucket(minioProperties.getBucketName())
                                .object(storagePath)
                                .build());
                String versionId = stat.versionId();
                if (versionId != null) {
                    minioClient.removeObject(
                            RemoveObjectArgs.builder()
                                    .bucket(minioProperties.getBucketName())
                                    .object(storagePath)
                                    .versionId(versionId)
                                    .build());
                    log.info("Reverted '{}' by deleting version '{}'", storagePath, versionId);
                }
            } catch (Exception e) {
                log.warn("Could not revert latest version of '{}': {}", storagePath, e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Flux<String> listFiles(String prefix) {
        return Flux.create(sink -> {
            try {
                Iterable<Result<Item>> results = minioClient.listObjects(
                        ListObjectsArgs.builder()
                                .bucket(minioProperties.getBucketName())
                                .prefix(prefix)
                                .recursive(true)
                                .build());

                for (Result<Item> result : results) {
                    sink.next(result.get().objectName());
                }
                sink.complete();
            } catch (Exception e) {
                sink.error(new StorageException("MinIO listFiles failed", e));
            }
        });
    }

}