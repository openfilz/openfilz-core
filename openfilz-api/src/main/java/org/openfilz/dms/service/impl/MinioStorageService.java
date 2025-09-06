// com/example/dms/service/impl/MinioStorageService.java
package org.openfilz.dms.service.impl;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.exception.StorageException;
import org.openfilz.dms.service.StorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

@Slf4j
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "minio")
public class MinioStorageService implements StorageService {

    private final MinioClient minioClient;
    private final String bucketName;

    @Value("${piped.buffer.size:1024}")
    private Integer pipedBufferSize;

    public MinioStorageService(
            @Value("${storage.minio.endpoint}") String endpoint,
            @Value("${storage.minio.access-key}") String accessKey,
            @Value("${storage.minio.secret-key}") String secretKey,
            @Value("${storage.minio.bucket-name}") String bucketName
    ) {
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.bucketName = bucketName;
        ensureBucketExists();
    }

    private void ensureBucketExists() {
        try {
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("Bucket '{}' created successfully.", bucketName);
            } else {
                log.info("Bucket '{}' already exists.", bucketName);
            }
        } catch (Exception e) {
            log.error("Error ensuring bucket '{}' exists", bucketName, e);
            throw new RuntimeException("MinIO bucket initialization failed", e);
        }
    }

    @Override
    public Mono<String> saveFile(FilePart filePart) {
        String originalFilename = filePart.filename();
        String objectName = getUniqueStorageFileName(originalFilename); // Ensure unique name

        // PipedInputStream will be read by MinIO client
        PipedInputStream pipedInputStream = new PipedInputStream(pipedBufferSize);
        // PipedOutputStream will be written to by our reactive stream
        // It's important to create PipedOutputStream with the PipedInputStream instance
        PipedOutputStream pipedOutputStream;
        try {
            pipedOutputStream = new PipedOutputStream(pipedInputStream);
        } catch (IOException e) {
            log.error("Failed to create PipedOutputStream", e);
            return Mono.error(e);
        }

        // This subscription writes data from FilePart to PipedOutputStream
        // DataBufferUtils.write consumes the Flux<DataBuffer> and writes to the OutputStream.
        // It releases DataBuffers. It completes when the flux is done.
        // We run this in a separate thread so it doesn't block the main reactive flow
        // waiting for PipedInputStream to be read.
        DataBufferUtils.write(filePart.content(), pipedOutputStream)
                .subscribeOn(Schedulers.boundedElastic()) // Use boundedElastic for I/O blocking work
                .doOnError(e -> {
                    log.error("Error writing file content to PipedOutputStream for {}", objectName, e);
                    try {
                        pipedOutputStream.close(); // Close stream on error
                    } catch (IOException ignored) {
                    }
                })
                .doOnComplete(() -> {
                    log.debug("Finished writing file content to PipedOutputStream for {}", objectName);
                    try {
                        pipedOutputStream.close(); // Essential to signal end of stream to PipedInputStream
                    } catch (IOException e) {
                        log.warn("Error closing PipedOutputStream for {}: {}", objectName, e.getMessage());
                    }
                })
                .subscribe(); // Fire and forget, error handling is above.

        // The MinIO upload part. This will block the thread it runs on until upload is complete.
        // So, we run it on a different scheduler.
        return Mono.fromCallable(() -> {
                    log.info("Starting MinIO putObject for {}", objectName);
                    // Use -1 for object size and part_size for MinIO to handle stream size automatically.
                    // MinIO SDK will read from pipedInputStream until it's closed.
                    // The SDK will buffer parts internally (default 5MiB to 5GiB depending on total size).
                    // This internal buffering by SDK is fine and won't exhaust 256MB for a single part.
                    PutObjectArgs args = PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(pipedInputStream, -1, PutObjectArgs.MIN_MULTIPART_SIZE) // Min part size is 5MiB
                            .contentType(filePart.headers().getContentType() != null ?
                                    filePart.headers().getContentType().toString() : "application/octet-stream")
                            .build();
                    minioClient.putObject(args);
                    log.info("Successfully uploaded {} to MinIO bucket {}", objectName, bucketName);
                    return objectName;
                })
                .subscribeOn(Schedulers.boundedElastic()) // Crucial: MinIO SDK's putObject is blocking
                .doOnTerminate(() -> { // Ensure PipedInputStream is closed regardless of outcome
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
                                .bucket(bucketName)
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
                                .bucket(bucketName)
                                .object(storagePath)
                                .build()
                );
                log.info("File '{}' deleted successfully from MinIO bucket '{}'", storagePath, bucketName);
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
                                .bucket(bucketName)
                                .object(destinationObjectName)
                                .source(CopySource.builder().bucket(bucketName).object(sourceStoragePath).build())
                                .build());
                log.info("File copied from {} to {} in MinIO bucket '{}'", sourceStoragePath, destinationObjectName, bucketName);
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
                        .bucket(bucketName)
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

}