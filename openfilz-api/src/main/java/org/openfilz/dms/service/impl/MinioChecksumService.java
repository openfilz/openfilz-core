package org.openfilz.dms.service.impl;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioAsyncClient;
import io.minio.errors.MinioException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.Checksum;
import org.openfilz.dms.service.ChecksumService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperties({
        @ConditionalOnProperty(name = "storage.type", havingValue = "minio"),
        @ConditionalOnProperty(name = "openfilz.calculate-checksum", havingValue = "true")
})
public class MinioChecksumService implements ChecksumService {

    private MinioAsyncClient minioAsyncClient;

    @Value("${storage.minio.endpoint}")
    private String endpoint;

    @Value("${storage.minio.access-key}")
    private String accessKey;

    @Value("${storage.minio.secret-key}")
    private String secretKey;

    @Value("${storage.minio.bucket-name}")
    private String bucketName;

    @Value("${piped.buffer.size:8192}")
    private Integer bufferSize;

    @PostConstruct
    public void init() {
        this.minioAsyncClient = MinioAsyncClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    @Override
    public Mono<Checksum> calculateChecksum(String storagePath, Map<String, Object> metadata) {
        return calculateSha256Checksum(storagePath)
                .flatMap(checksum -> getChecksumMono(storagePath, metadata, checksum));
    }

    /**
     * Calculate SHA-256 checksum of a MinIO object in streaming mode.
     * Uses reactive pipeline for minimal memory footprint.
     *
     * @param objectName the object name/key
     * @return Mono containing the hex-encoded SHA-256 checksum
     */
    public Mono<String> calculateSha256Checksum(String objectName) {
        return Mono.fromCompletionStage(() ->
                    {
                        try {
                            return minioAsyncClient.getObject(
                                    GetObjectArgs.builder()
                                            .bucket(bucketName)
                                            .object(objectName)
                                            .build()
                            );
                        } catch (MinioException | InvalidKeyException | IOException | NoSuchAlgorithmException e) {
                            throw new RuntimeException(e);
                        }
                    }
                )
                .flatMap(this::streamAndCalculateChecksum)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Stream the response and calculate checksum reactively.
     */
    private Mono<String> streamAndCalculateChecksum(GetObjectResponse response) {
        return Mono.fromCallable(() -> MessageDigest.getInstance("SHA-256"))
                .flatMap(digest ->
                        readInputStreamAsFlux(response, bufferSize)
                                .doOnNext(buffer -> {
                                    // Update digest with each chunk
                                    if (buffer.hasRemaining()) {
                                        digest.update(buffer);
                                    }
                                })
                                .doFinally(signalType -> {
                                    // Ensure stream is closed
                                    try {
                                        response.close();
                                    } catch (Exception e) {
                                        log.error("Error while closing response stream", e);
                                    }
                                })
                                .then(Mono.fromCallable(() -> {
                                    // Calculate final hash
                                    byte[] hashBytes = digest.digest();
                                    return HexFormat.of().formatHex(hashBytes);
                                }))
                )
                .onErrorResume(e -> {
                    try {
                        response.close();
                    } catch (Exception ex) {
                        // Suppress close exception
                        log.error("Error while closing response stream", e);
                    }
                    return Mono.error(e);
                });
    }

    /**
     * Convert InputStream to Flux of ByteBuffers for reactive processing.
     */
    private Flux<ByteBuffer> readInputStreamAsFlux(InputStream inputStream, int bufferSize) {
        return Flux.create(sink -> {
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    byte[] buffer = new byte[bufferSize];
                    int bytesRead;

                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        if (sink.isCancelled()) {
                            break;
                        }
                        // Create a copy of the buffer portion that was read
                        byte[] data = new byte[bytesRead];
                        System.arraycopy(buffer, 0, data, 0, bytesRead);
                        sink.next(ByteBuffer.wrap(data));
                    }

                    sink.complete();
                } catch (Exception e) {
                    sink.error(e);
                } finally {
                    try {
                        inputStream.close();
                    } catch (Exception e) {
                        // Log if needed
                    }
                }
            });
        });
    }
}
