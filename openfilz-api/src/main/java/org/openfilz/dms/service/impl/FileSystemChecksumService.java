package org.openfilz.dms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.Checksum;
import org.openfilz.dms.service.ChecksumService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@Service
@Slf4j
@ConditionalOnProperties({
        @ConditionalOnProperty(name = "storage.type", havingValue = "local"),
        @ConditionalOnProperty(name = "openfilz.calculate-checksum", havingValue = "true")

})
public class FileSystemChecksumService implements ChecksumService {

    @Value("${piped.buffer.size:8192}")
    private Integer bufferSize;

    private final Path rootLocation;

    private final DefaultDataBufferFactory bufferFactory;

    public FileSystemChecksumService(FileSystemStorageService fileSystemStorageService) {
        this.rootLocation = fileSystemStorageService.getRootLocation();
        this.bufferFactory = new DefaultDataBufferFactory();
    }


    @Override
    public Mono<Checksum> calculateChecksum(String storagePath, Map<String, Object> metadata) {
        Path file = rootLocation.resolve(storagePath).normalize();
        return calculateSha256Checksum(file).flatMap(checksum -> getChecksumMono(storagePath, metadata, checksum));
    }


    /**
     * Calculate SHA-256 checksum using Spring's DataBufferUtils (most idiomatic for WebFlux).
     * This is the recommended approach as it integrates perfectly with Spring WebFlux.
     *
     * @param filePath the path to the file
     * @return Mono containing the hex-encoded SHA-256 checksum
     */
    public Mono<String> calculateSha256Checksum(Path filePath) {
        return Mono.fromCallable(() -> MessageDigest.getInstance(SHA_256))
                .flatMap(digest ->
                        DataBufferUtils.read(
                                        filePath,
                                        bufferFactory,
                                        bufferSize,
                                        StandardOpenOption.READ
                                )
                                .doOnNext(dataBuffer -> {
                                    try {
                                        // Update digest with buffer content
                                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                        dataBuffer.read(bytes); // Read data from DataBuffer into the byte array
                                        digest.update(bytes);
                                    } finally {
                                        // CRITICAL: Release buffer to prevent memory leaks
                                        DataBufferUtils.release(dataBuffer);
                                    }
                                })
                                .then(Mono.fromCallable(() -> {
                                    byte[] hashBytes = digest.digest();
                                    return HexFormat.of().formatHex(hashBytes);
                                }))
                )
                .subscribeOn(Schedulers.boundedElastic());
    }

}
