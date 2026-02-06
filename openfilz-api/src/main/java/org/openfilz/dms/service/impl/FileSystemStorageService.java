// com/example/dms/service/impl/LocalStorageService.java
package org.openfilz.dms.service.impl;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.exception.StorageException;
import org.openfilz.dms.service.StorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "local")
public class FileSystemStorageService implements StorageService {

    @Getter
    private final Path rootLocation;

    public FileSystemStorageService(@Value("${storage.local.base-path:/tmp/dms-storage}") String basePath) {
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
            log.debug("Loading file: {} -> {}", storagePath, file.toAbsolutePath());
            Resource resource = new PathResource(file);
            if (resource.exists() && resource.isReadable()) {
                return resource;
            } else {
                log.warn("Could not read file: {} (exists={}, readable={})",
                        file.toAbsolutePath(), resource.exists(), resource.isReadable());
                throw new RuntimeException("Could not read file: " + storagePath);
            }
        }).subscribeOn(Schedulers.boundedElastic())
        .onErrorMap(e -> {
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

    // ==================== TUS Upload Support Methods ====================

    @Override
    public Mono<Void> createEmptyFile(String storagePath) {
        return Mono.fromRunnable(() -> {
            try {
                Path filePath = rootLocation.resolve(storagePath).normalize();
                // Create parent directories if they don't exist
                Files.createDirectories(filePath.getParent());
                Files.createFile(filePath);
                log.debug("Created empty file: {}", filePath);
            } catch (FileAlreadyExistsException e) {
                log.debug("File already exists: {}", storagePath);
            } catch (IOException e) {
                log.error("Could not create empty file: {}", storagePath, e);
                throw new StorageException("Could not create empty file: " + storagePath, e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Long> appendData(String storagePath, Flux<DataBuffer> data, long offset) {
        return Mono.defer(() -> {
            Path filePath = rootLocation.resolve(storagePath).normalize();
            AtomicLong bytesWritten = new AtomicLong(0);
            AtomicLong position = new AtomicLong(offset);

            return Mono.usingWhen(
                    // Open file channel
                    Mono.fromCallable(() -> AsynchronousFileChannel.open(
                            filePath,
                            StandardOpenOption.WRITE
                    )).subscribeOn(Schedulers.boundedElastic()),

                    // Write data chunks sequentially
                    channel -> data.concatMap(dataBuffer -> {
                        ByteBuffer byteBuffer = dataBuffer.toByteBuffer();
                        int remaining = byteBuffer.remaining();
                        long currentPosition = position.getAndAdd(remaining);

                        return Mono.<Void>create(sink -> {
                            channel.write(byteBuffer, currentPosition, null,
                                    new java.nio.channels.CompletionHandler<Integer, Void>() {
                                        @Override
                                        public void completed(Integer result, Void attachment) {
                                            bytesWritten.addAndGet(result);
                                            DataBufferUtils.release(dataBuffer);
                                            sink.success();
                                        }

                                        @Override
                                        public void failed(Throwable exc, Void attachment) {
                                            DataBufferUtils.release(dataBuffer);
                                            sink.error(exc);
                                        }
                                    });
                        });
                    }).then(),

                    // Close channel on success
                    channel -> closeChannel(channel),
                    // Close channel on error
                    (channel, err) -> closeChannel(channel),
                    // Close channel on cancel
                    channel -> closeChannel(channel)
            ).then(Mono.fromCallable(() -> {
                // Return new file size
                return offset + bytesWritten.get();
            }));
        });
    }

    private Mono<Void> closeChannel(AsynchronousFileChannel channel) {
        return Mono.fromRunnable(() -> {
            try {
                channel.close();
            } catch (IOException e) {
                log.warn("Error closing file channel", e);
            }
        });
    }

    @Override
    public Mono<Void> saveData(String storagePath, Flux<DataBuffer> data) {
        return Mono.defer(() -> {
            Path filePath = rootLocation.resolve(storagePath).normalize();
            log.debug("saveData: storagePath={}, filePath={}", storagePath, filePath.toAbsolutePath());

            // Create parent directories if needed
            try {
                Path parentDir = filePath.getParent();
                Files.createDirectories(parentDir);
                log.debug("saveData: created directories for {}", parentDir.toAbsolutePath());
            } catch (IOException e) {
                log.error("saveData: failed to create directories for {}", storagePath, e);
                return Mono.error(new StorageException("Could not create directories for: " + storagePath, e));
            }

            AtomicLong position = new AtomicLong(0);

            return Mono.usingWhen(
                    // Open file channel with CREATE and TRUNCATE to overwrite
                    Mono.fromCallable(() -> AsynchronousFileChannel.open(
                            filePath,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING
                    )).subscribeOn(Schedulers.boundedElastic()),

                    // Write data chunks sequentially
                    channel -> data.concatMap(dataBuffer -> {
                        ByteBuffer byteBuffer = dataBuffer.toByteBuffer();
                        int remaining = byteBuffer.remaining();
                        long currentPosition = position.getAndAdd(remaining);

                        return Mono.<Void>create(sink -> {
                            channel.write(byteBuffer, currentPosition, null,
                                    new java.nio.channels.CompletionHandler<Integer, Void>() {
                                        @Override
                                        public void completed(Integer result, Void attachment) {
                                            DataBufferUtils.release(dataBuffer);
                                            sink.success();
                                        }

                                        @Override
                                        public void failed(Throwable exc, Void attachment) {
                                            DataBufferUtils.release(dataBuffer);
                                            sink.error(exc);
                                        }
                                    });
                        });
                    }).then(),

                    // Close channel
                    this::closeChannel,
                    (channel, err) -> closeChannel(channel),
                    this::closeChannel
            ).doOnSuccess(v -> {
                try {
                    long size = Files.size(filePath);
                    log.debug("saveData: successfully wrote {} bytes to {}", size, filePath.toAbsolutePath());
                } catch (IOException e) {
                    log.debug("saveData: completed but could not get file size for {}", filePath);
                }
            }).doOnError(e -> log.error("saveData: failed to write to {}", filePath, e));
        });
    }

    @Override
    public Mono<Void> moveFile(String sourcePath, String destPath) {
        return Mono.fromRunnable(() -> {
            try {
                Path source = rootLocation.resolve(sourcePath).normalize();
                Path dest = rootLocation.resolve(destPath).normalize();
                // Create parent directories if they don't exist
                Files.createDirectories(dest.getParent());
                Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING);
                log.info("File moved from {} to {}", sourcePath, destPath);
            } catch (IOException e) {
                log.error("Could not move file from {} to {}", sourcePath, destPath, e);
                throw new StorageException("Could not move file", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Flux<String> listFiles(String prefix) {
        return Flux.create(sink -> {
            try {
                Path prefixPath = rootLocation.resolve(prefix).normalize();
                if (!Files.exists(prefixPath)) {
                    sink.complete();
                    return;
                }

                Files.walkFileTree(prefixPath, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        // Return path relative to rootLocation
                        String relativePath = rootLocation.relativize(file).toString().replace("\\", "/");
                        sink.next(relativePath);
                        return FileVisitResult.CONTINUE;
                    }
                });
                sink.complete();
            } catch (IOException e) {
                sink.error(new StorageException("Could not list files under prefix: " + prefix, e));
            }
        });
    }

}