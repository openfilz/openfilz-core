package org.openfilz.dms.config;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.ThumbnailStorageService;
import org.openfilz.dms.service.ChecksumService;
import org.openfilz.dms.service.impl.FileSystemStorageService;
import org.openfilz.dms.service.impl.MinioStorageService;
import org.openfilz.dms.service.impl.FileSystemThumbnailStorageService;
import org.openfilz.dms.service.impl.MinioThumbnailStorageService;
import org.openfilz.dms.service.impl.FileSystemChecksumService;
import org.openfilz.dms.service.impl.MinioChecksumService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Runtime storage backend selection.
 * <p>
 * Both FileSystem and MinIO implementations are registered as {@code @Lazy @Qualifier} beans
 * so they are included in the native image but not eagerly initialized.
 * This configuration selects the active implementation at runtime based on {@code storage.type}.
 * The unused implementation is never initialized thanks to {@code @Lazy}.
 */
@Slf4j
@Configuration
public class StorageConfig {

    @Bean
    @Primary
    public StorageService storageService(
            @Value("${storage.type:local}") String storageType,
            ObjectProvider<FileSystemStorageService> localProvider,
            ObjectProvider<MinioStorageService> minioProvider) {
        if ("minio".equals(storageType)) {
            log.info("Storage backend: MinIO");
            return minioProvider.getIfAvailable();
        }
        log.info("Storage backend: local filesystem");
        return localProvider.getIfAvailable();
    }

    @Bean
    @Primary
    public ThumbnailStorageService thumbnailStorageService(
            @Value("${storage.type:local}") String storageType,
            ObjectProvider<FileSystemThumbnailStorageService> localProvider,
            ObjectProvider<MinioThumbnailStorageService> minioProvider) {
        if ("minio".equals(storageType)) {
            ThumbnailStorageService svc = minioProvider.getIfAvailable();
            if (svc != null) {
                log.info("Thumbnail storage backend: MinIO");
                return svc;
            }
        }
        ThumbnailStorageService svc = localProvider.getIfAvailable();
        if (svc != null) {
            log.info("Thumbnail storage backend: local filesystem");
        }
        return svc;
    }

    @Bean
    @Primary
    public ChecksumService checksumService(
            @Value("${storage.type:local}") String storageType,
            ObjectProvider<FileSystemChecksumService> localProvider,
            ObjectProvider<MinioChecksumService> minioProvider) {
        if ("minio".equals(storageType)) {
            ChecksumService svc = minioProvider.getIfAvailable();
            if (svc != null) {
                log.info("Checksum service backend: MinIO");
                return svc;
            }
        }
        ChecksumService svc = localProvider.getIfAvailable();
        if (svc != null) {
            log.info("Checksum service backend: local filesystem");
        }
        return svc;
    }
}
