// com/example/dms/service/impl/LocalStorageService.java
package org.openfilz.dms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.exception.StorageException;
import org.openfilz.dms.service.StorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.*;

@Slf4j
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "local")
public class LocalStorageService implements StorageService {

    private final Path rootLocation;

    public LocalStorageService(@Value("${storage.local.base-path:/tmp/dms-storage}") String basePath) {
        this.rootLocation = Paths.get(basePath);
        try {
            Files.createDirectories(rootLocation);
            log.info("Local storage initialized at: {}", rootLocation.toAbsolutePath());
        } catch (IOException e) {
            log.error("Could not initialize storage location: {}", basePath, e);
            throw new RuntimeException("Could not initialize storage", e);
        }
    }

    @Override
    public Mono<String> saveFile(FilePart filePart) {
        String originalFilename = filePart.filename();
        String storageFileName = getUniqueStorageFileName(originalFilename);
        Path destinationFile = this.rootLocation.resolve(storageFileName).normalize();
        return filePart.transferTo(destinationFile)
                .thenReturn(storageFileName) // Return relative path to be stored
                .doOnSuccess(path -> log.info("File saved to: {}", destinationFile));
    }

    @Override
    public Mono<Resource> loadFile(String storagePath) {
        return Mono.fromCallable(() -> {
            Path file = rootLocation.resolve(storagePath).normalize();
            Resource resource = new PathResource(file);
            if (resource.exists() || resource.isReadable()) {
                return resource;
            } else {
                log.warn("Could not read file: {} or file does not exist", storagePath);
                throw new RuntimeException("Could not read file: " + storagePath);
            }
        }).onErrorMap(e -> {
            log.error("Error loading file {}: {}", storagePath, e.getMessage());
            return new RuntimeException("Error loading file " + storagePath, e);
        });
    }

    @Override
    public Mono<Void> deleteFile(String storagePath) {
        return Mono.fromRunnable(() -> {
            try {
                Path filePath = rootLocation.resolve(storagePath).normalize();
                Files.delete(filePath);
                log.info("File deleted: {}", storagePath);
            } catch (NoSuchFileException e) {
                log.warn("File {} not found in Local Storage for deletion, presumed already deleted.", storagePath);
            } catch (IOException e) {
                log.error("Could not delete file: {}", storagePath, e);
                throw new RuntimeException("Could not delete file: " + storagePath, e);
            }
        });
    }

    @Override
    public Mono<String> copyFile(String sourceStoragePath) {
        Path sourceFile = rootLocation.resolve(sourceStoragePath);
        // Potentially make the copied file name unique or retain original based on destination prefix logic
        String uniqueFilename = getUniqueStorageFileName(sourceFile.getFileName().toString());// Or derive from destinationStoragePathPrefix
        Path destinationFile = this.rootLocation.resolve(uniqueFilename).normalize();

        return Mono.fromRunnable(() -> {
            try {
                //Files.createDirectories(destinationFolder);
                Files.copy(sourceFile, destinationFile, StandardCopyOption.REPLACE_EXISTING);
                log.info("File copied from {} to {}", sourceStoragePath, destinationFile);
            } catch (IOException e) {
                log.error("Could not copy file from {} to {}", sourceStoragePath, destinationFile, e);
                throw new RuntimeException("Could not copy file", e);
            }
        }).thenReturn(uniqueFilename);
    }

    @Override
    public Mono<Long> getFileLength(String storagePath) {
        Path sourceFile = rootLocation.resolve(storagePath);
        try {
            return Mono.just(Files.size(sourceFile));
        } catch (IOException e) {
            throw new StorageException(e);
        }
    }

}