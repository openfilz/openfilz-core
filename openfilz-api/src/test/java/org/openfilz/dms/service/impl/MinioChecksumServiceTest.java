package org.openfilz.dms.service.impl;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MinioAsyncClient;
import io.minio.Result;
import io.minio.messages.Item;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.config.MinioProperties;
import org.openfilz.dms.dto.Checksum;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MinioChecksumServiceTest {

    @Mock
    private MinioProperties minioProperties;

    @Mock
    private MinioAsyncClient minioAsyncClient;

    private MinioChecksumService service;

    @BeforeEach
    void setUp() {
        lenient().when(minioProperties.getBucketName()).thenReturn("test-bucket");
        lenient().when(minioProperties.getEndpoint()).thenReturn("http://localhost:9000");
        lenient().when(minioProperties.getAccessKey()).thenReturn("minioadmin");
        lenient().when(minioProperties.getSecretKey()).thenReturn("minioadmin");

        service = new MinioChecksumService(minioProperties);
        ReflectionTestUtils.setField(service, "minioAsyncClient", minioAsyncClient);
        ReflectionTestUtils.setField(service, "bufferSize", 8192);
    }

    @Test
    void calculateSha256Checksum_withData_returnsHexHash() throws Exception {
        byte[] content = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream inputStream = new ByteArrayInputStream(content);
        GetObjectResponse mockResponse = new GetObjectResponse(
                Headers.of(Map.of()), "test-bucket", "", "test-object", inputStream);

        when(minioAsyncClient.getObject(any(GetObjectArgs.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        StepVerifier.create(service.calculateSha256Checksum("test-object"))
                .expectNextMatches(hash -> {
                    assertNotNull(hash);
                    // SHA-256 of "Hello, World!" is known
                    assertEquals("dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f", hash);
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void calculateSha256Checksum_withEmptyData_returnsEmptyHash() throws Exception {
        byte[] content = new byte[0];
        ByteArrayInputStream inputStream = new ByteArrayInputStream(content);
        GetObjectResponse mockResponse = new GetObjectResponse(
                Headers.of(Map.of()), "test-bucket", "", "test-object", inputStream);

        when(minioAsyncClient.getObject(any(GetObjectArgs.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        StepVerifier.create(service.calculateSha256Checksum("test-object"))
                .expectNextMatches(hash -> {
                    assertNotNull(hash);
                    // SHA-256 of empty string
                    assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash);
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void calculateChecksum_returnsChecksumWithMetadata() throws Exception {
        byte[] content = "test content".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream inputStream = new ByteArrayInputStream(content);
        GetObjectResponse mockResponse = new GetObjectResponse(
                Headers.of(Map.of()), "test-bucket", "", "test-object", inputStream);

        when(minioAsyncClient.getObject(any(GetObjectArgs.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        Map<String, Object> metadata = Map.of("key", "value");

        StepVerifier.create(service.calculateChecksum("test-object", metadata))
                .expectNextMatches(checksum -> {
                    assertNotNull(checksum);
                    assertEquals("test-object", checksum.storagePath());
                    assertNotNull(checksum.metadataWithChecksum());
                    assertTrue(checksum.metadataWithChecksum().containsKey("sha256"));
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void calculateSha256Checksum_error_propagates() throws Exception {
        when(minioAsyncClient.getObject(any(GetObjectArgs.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Connection refused")));

        StepVerifier.create(service.calculateSha256Checksum("test-object"))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void calculatePreviousVersionChecksum_withPreviousVersion_returnsChecksum() throws Exception {
        // Setup listObjects to return two versions - latest and previous
        Item latestItem = mock(Item.class);
        when(latestItem.isLatest()).thenReturn(true);

        Item previousItem = mock(Item.class);
        when(previousItem.isLatest()).thenReturn(false);
        when(previousItem.versionId()).thenReturn("prev-version-id");

        Result<Item> result1 = mock(Result.class);
        when(result1.get()).thenReturn(latestItem);
        Result<Item> result2 = mock(Result.class);
        when(result2.get()).thenReturn(previousItem);

        Iterable<Result<Item>> results = List.of(result1, result2);
        when(minioAsyncClient.listObjects(any(ListObjectsArgs.class))).thenReturn(results);

        // Setup getObject for the previous version
        byte[] content = "previous content".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream inputStream = new ByteArrayInputStream(content);
        GetObjectResponse mockResponse = new GetObjectResponse(
                Headers.of(Map.of()), "test-bucket", "", "test-object", inputStream);

        when(minioAsyncClient.getObject(any(GetObjectArgs.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        StepVerifier.create(service.calculatePreviousVersionChecksum("test-object"))
                .expectNextMatches(hash -> {
                    assertNotNull(hash);
                    assertTrue(hash.length() == 64); // SHA-256 hex is 64 chars
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void calculatePreviousVersionChecksum_noPreviousVersion_returnsEmpty() throws Exception {
        // Only the latest version, no previous
        Item latestItem = mock(Item.class);
        when(latestItem.isLatest()).thenReturn(true);

        Result<Item> result1 = mock(Result.class);
        when(result1.get()).thenReturn(latestItem);

        Iterable<Result<Item>> results = List.of(result1);
        when(minioAsyncClient.listObjects(any(ListObjectsArgs.class))).thenReturn(results);

        // getPreviousVersionId returns null → flatMap returns empty Mono
        StepVerifier.create(service.calculatePreviousVersionChecksum("test-object"))
                .verifyComplete();
    }

    @Test
    void calculateSha256Checksum_withLargeData_processesInChunks() throws Exception {
        // Create data larger than the buffer size to test chunked processing
        byte[] content = new byte[16384]; // 16KB > 8192 buffer
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 256);
        }
        ByteArrayInputStream inputStream = new ByteArrayInputStream(content);
        GetObjectResponse mockResponse = new GetObjectResponse(
                Headers.of(Map.of()), "test-bucket", "", "test-object", inputStream);

        when(minioAsyncClient.getObject(any(GetObjectArgs.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        StepVerifier.create(service.calculateSha256Checksum("test-object"))
                .expectNextMatches(hash -> {
                    assertNotNull(hash);
                    assertEquals(64, hash.length()); // SHA-256 produces 64 hex chars
                    return true;
                })
                .verifyComplete();
    }
}
