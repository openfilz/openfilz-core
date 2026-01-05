package org.openfilz.dms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.ThumbnailProperties;
import org.openfilz.dms.service.ThumbnailStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/**
 * File system implementation of ThumbnailStorageService.
 * Stores thumbnails in a local directory as PNG files.
 * <p>
 * Activated when:
 * - Thumbnail feature is active AND
 * - Either useMainStorage=true with storage.type=local, OR useMainStorage=false with thumbnail.storage.type=local
 */
@Slf4j
@Service
@ConditionalOnExpression(
    "${openfilz.thumbnail.active:false} and " +
    "((${openfilz.thumbnail.storage.use-main-storage:true} and '${storage.type:local}' == 'local') or " +
    "(!${openfilz.thumbnail.storage.use-main-storage:true} and '${openfilz.thumbnail.storage.type:local}' == 'local'))"
)
public class FileSystemThumbnailStorageService implements ThumbnailStorageService {

    private static final String THUMBNAILS_FOLDER = "thumbnails";

    private final Path thumbnailsPath;

    public FileSystemThumbnailStorageService(
            ThumbnailProperties thumbnailProperties,
            @Value("${storage.local.base-path:/tmp/dms-storage}") String mainBasePath) {

        // Determine base path for thumbnails
        String basePath;
        if (thumbnailProperties.getStorage().isUseMainStorage()) {
            basePath = mainBasePath;
        } else {
            basePath = thumbnailProperties.getStorage().getLocal().getBasePath();
            if (basePath == null || basePath.isBlank()) {
                basePath = mainBasePath;
            }
        }

        this.thumbnailsPath = Paths.get(basePath, THUMBNAILS_FOLDER);
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(thumbnailsPath);
            log.info("Thumbnail storage initialized at: {}", thumbnailsPath.toAbsolutePath());
        } catch (IOException e) {
            log.error("Could not initialize thumbnail storage location: {}", thumbnailsPath, e);
            throw new RuntimeException("Could not initialize thumbnail storage", e);
        }
    }

    @Override
    public Mono<Void> saveThumbnail(UUID documentId, byte[] thumbnailBytes, String format) {
        return Mono.fromRunnable(() -> {
            try {
                Path file = getThumbnailPath(documentId);
                Files.write(file, thumbnailBytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
                log.debug("Thumbnail saved for document: {}", documentId);
            } catch (IOException e) {
                log.error("Failed to save thumbnail for document: {}", documentId, e);
                throw new RuntimeException("Failed to save thumbnail", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<byte[]> loadThumbnail(UUID documentId) {
        return Mono.fromCallable(() -> {
            Path file = getThumbnailPath(documentId);
            if (Files.exists(file)) {
                return Files.readAllBytes(file);
            }
            return null;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> deleteThumbnail(UUID documentId) {
        return Mono.fromRunnable(() -> {
            try {
                Path file = getThumbnailPath(documentId);
                Files.deleteIfExists(file);
                log.debug("Thumbnail deleted for document: {}", documentId);
            } catch (IOException e) {
                log.warn("Failed to delete thumbnail for document: {}", documentId, e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Void> copyThumbnail(UUID sourceId, UUID targetId) {
        return Mono.fromRunnable(() -> {
            try {
                Path source = getThumbnailPath(sourceId);
                Path target = getThumbnailPath(targetId);
                if (Files.exists(source)) {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    log.debug("Thumbnail copied from {} to {}", sourceId, targetId);
                }
            } catch (IOException e) {
                log.warn("Failed to copy thumbnail from {} to {}", sourceId, targetId, e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Boolean> thumbnailExists(UUID documentId) {
        return Mono.fromCallable(() -> {
            Path file = getThumbnailPath(documentId);
            return Files.exists(file);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Gets the path for a thumbnail file.
     * Thumbnail filename is {documentId}.png
     */
    private Path getThumbnailPath(UUID documentId) {
        return thumbnailsPath.resolve(documentId.toString());
    }
}
