package org.openfilz.dms.service.impl;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.ThumbnailProperties;
import org.openfilz.dms.service.ThumbnailStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;

/**
 * MinIO/S3 implementation of ThumbnailStorageService.
 * Stores thumbnails in a MinIO bucket as PNG files.
 * <p>
 * Activated when:
 * - Thumbnail feature is active AND
 * - Either useMainStorage=true with storage.type=minio, OR useMainStorage=false with thumbnail.storage.type=minio
 */
@Slf4j
@Service
@ConditionalOnExpression(
    "${openfilz.thumbnail.active:false} and " +
    "((${openfilz.thumbnail.storage.use-main-storage:true} and '${storage.type:local}' == 'minio') or " +
    "(!${openfilz.thumbnail.storage.use-main-storage:true} and '${openfilz.thumbnail.storage.type:local}' == 'minio'))"
)
public class MinioThumbnailStorageService implements ThumbnailStorageService {

    private static final String THUMBNAIL_PREFIX = "thumbnails/";
    private static final String CONTENT_TYPE = "image/png";
    private static final String IMAGE = "image/";

    private final ThumbnailProperties thumbnailProperties;
    private final String mainBucketName;
    private final String mainEndpoint;
    private final String mainAccessKey;
    private final String mainSecretKey;

    private MinioClient minioClient;
    private String bucketName;

    public MinioThumbnailStorageService(
            ThumbnailProperties thumbnailProperties,
            @Value("${storage.minio.bucket-name:dms-bucket}") String mainBucketName,
            @Value("${storage.minio.endpoint:http://localhost:9000}") String mainEndpoint,
            @Value("${storage.minio.access-key:minioadmin}") String mainAccessKey,
            @Value("${storage.minio.secret-key:minioadmin}") String mainSecretKey) {
        this.thumbnailProperties = thumbnailProperties;
        this.mainBucketName = mainBucketName;
        this.mainEndpoint = mainEndpoint;
        this.mainAccessKey = mainAccessKey;
        this.mainSecretKey = mainSecretKey;
    }

    @PostConstruct
    public void init() {
        // Determine endpoint and credentials
        String endpoint;
        String accessKey;
        String secretKey;

        if (thumbnailProperties.getStorage().isUseMainStorage()) {
            endpoint = mainEndpoint;
            accessKey = mainAccessKey;
            secretKey = mainSecretKey;
            bucketName = mainBucketName;
        } else {
            ThumbnailProperties.Storage.Minio minioConfig = thumbnailProperties.getStorage().getMinio();
            endpoint = minioConfig.getEndpoint() != null ? minioConfig.getEndpoint() : mainEndpoint;
            accessKey = minioConfig.getAccessKey() != null ? minioConfig.getAccessKey() : mainAccessKey;
            secretKey = minioConfig.getSecretKey() != null ? minioConfig.getSecretKey() : mainSecretKey;
            bucketName = minioConfig.getBucketName() != null ? minioConfig.getBucketName() : "dms-thumbnails";
        }

        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();

        ensureBucketExists();
        log.info("MinIO Thumbnail storage initialized with bucket: {}", bucketName);
    }

    private void ensureBucketExists() {
        try {
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("Thumbnail bucket '{}' created successfully.", bucketName);
            }
        } catch (Exception e) {
            log.error("Error ensuring thumbnail bucket '{}' exists", bucketName, e);
            throw new RuntimeException("Could not initialize thumbnail storage bucket", e);
        }
    }

    @Override
    public Mono<Void> saveThumbnail(UUID documentId, byte[] thumbnailBytes, String format) {
        return Mono.fromRunnable(() -> {
            try {
                String objectName = getThumbnailObjectName(documentId);
                ByteArrayInputStream inputStream = new ByteArrayInputStream(thumbnailBytes);

                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucketName)
                                .object(objectName)
                                .stream(inputStream, thumbnailBytes.length, -1)
                                .contentType(format == null ? CONTENT_TYPE : IMAGE + format)
                                .build()
                );
                log.debug("Thumbnail saved for document: {}", documentId);
            } catch (Exception e) {
                log.error("Failed to save thumbnail for document: {}", documentId, e);
                throw new RuntimeException("Failed to save thumbnail to MinIO", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<byte[]> loadThumbnail(UUID documentId) {
        return Mono.fromCallable(() -> {
            try {
                String objectName = getThumbnailObjectName(documentId);
                try (InputStream stream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket(bucketName)
                                .object(objectName)
                                .build())) {
                    return stream.readAllBytes();
                }
            } catch (ErrorResponseException e) {
                if ("NoSuchKey".equals(e.errorResponse().code())) {
                    return null;
                }
                throw e;
            } catch (Exception e) {
                log.error("Error loading thumbnail for document: {}", documentId, e);
                return null;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> deleteThumbnail(UUID documentId) {
        return Mono.fromRunnable(() -> {
            try {
                String objectName = getThumbnailObjectName(documentId);
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket(bucketName)
                                .object(objectName)
                                .build()
                );
                log.debug("Thumbnail deleted for document: {}", documentId);
            } catch (ErrorResponseException e) {
                if (!"NoSuchKey".equals(e.errorResponse().code())) {
                    log.warn("Failed to delete thumbnail for document: {}", documentId, e);
                }
            } catch (Exception e) {
                log.warn("Failed to delete thumbnail for document: {}", documentId, e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Void> copyThumbnail(UUID sourceId, UUID targetId) {
        return Mono.fromRunnable(() -> {
            try {
                String sourceObject = getThumbnailObjectName(sourceId);
                String targetObject = getThumbnailObjectName(targetId);

                // Check if source exists
                if (!thumbnailExistsSync(sourceId)) {
                    log.debug("Source thumbnail not found for copy: {}", sourceId);
                    return;
                }

                minioClient.copyObject(
                        CopyObjectArgs.builder()
                                .bucket(bucketName)
                                .object(targetObject)
                                .source(CopySource.builder()
                                        .bucket(bucketName)
                                        .object(sourceObject)
                                        .build())
                                .build()
                );
                log.debug("Thumbnail copied from {} to {}", sourceId, targetId);
            } catch (Exception e) {
                log.warn("Failed to copy thumbnail from {} to {}", sourceId, targetId, e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Boolean> thumbnailExists(UUID documentId) {
        return Mono.fromCallable(() -> thumbnailExistsSync(documentId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private boolean thumbnailExistsSync(UUID documentId) {
        try {
            String objectName = getThumbnailObjectName(documentId);
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            log.error("Error checking thumbnail existence for document: {}", documentId, e);
            return false;
        } catch (Exception e) {
            log.error("Error checking thumbnail existence for document: {}", documentId, e);
            return false;
        }
    }

    private String getThumbnailObjectName(UUID documentId) {
        return THUMBNAIL_PREFIX + documentId.toString();
    }
}
