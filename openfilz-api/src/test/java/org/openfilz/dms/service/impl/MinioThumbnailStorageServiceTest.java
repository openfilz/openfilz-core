package org.openfilz.dms.service.impl;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.config.MinioProperties;
import org.openfilz.dms.config.ThumbnailProperties;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MinioThumbnailStorageServiceTest {

    @Mock
    private MinioProperties minioProperties;

    @Mock
    private MinioClient minioClient;

    private MinioThumbnailStorageService service;

    @BeforeEach
    void setUp() {
        ThumbnailProperties thumbnailProperties = new ThumbnailProperties();
        thumbnailProperties.getStorage().setUseMainStorage(true);

        lenient().when(minioProperties.getEndpoint()).thenReturn("http://localhost:9000");
        lenient().when(minioProperties.getAccessKey()).thenReturn("minioadmin");
        lenient().when(minioProperties.getSecretKey()).thenReturn("minioadmin");
        lenient().when(minioProperties.getBucketName()).thenReturn("test-bucket");

        service = new MinioThumbnailStorageService(thumbnailProperties, minioProperties, Optional.empty());
        ReflectionTestUtils.setField(service, "minioClient", minioClient);
        ReflectionTestUtils.setField(service, "bucketName", "test-bucket");
    }

    @Test
    void saveThumbnail_success_completes() throws Exception {
        when(minioClient.putObject(any(PutObjectArgs.class)))
                .thenReturn(mock(ObjectWriteResponse.class));

        StepVerifier.create(service.saveThumbnail(UUID.randomUUID(), new byte[]{1, 2, 3}, "png"))
                .verifyComplete();

        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    void saveThumbnail_withNullFormat_usesDefaultContentType() throws Exception {
        when(minioClient.putObject(any(PutObjectArgs.class)))
                .thenReturn(mock(ObjectWriteResponse.class));

        StepVerifier.create(service.saveThumbnail(UUID.randomUUID(), new byte[]{1, 2, 3}, null))
                .verifyComplete();

        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    void saveThumbnail_error_throwsException() throws Exception {
        when(minioClient.putObject(any(PutObjectArgs.class)))
                .thenThrow(new RuntimeException("Upload failed"));

        StepVerifier.create(service.saveThumbnail(UUID.randomUUID(), new byte[]{1, 2, 3}, "png"))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void loadThumbnail_success_returnsBytes() throws Exception {
        GetObjectResponse mockResponse = mock(GetObjectResponse.class);
        when(mockResponse.readAllBytes()).thenReturn(new byte[]{10, 20, 30});
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResponse);

        StepVerifier.create(service.loadThumbnail(UUID.randomUUID()))
                .expectNextMatches(bytes -> {
                    assertArrayEquals(new byte[]{10, 20, 30}, bytes);
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void loadThumbnail_noSuchKey_returnsEmpty() throws Exception {
        ErrorResponse errorResponse = mock(ErrorResponse.class);
        when(errorResponse.code()).thenReturn("NoSuchKey");
        ErrorResponseException ex = mock(ErrorResponseException.class);
        when(ex.errorResponse()).thenReturn(errorResponse);

        when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(ex);

        // NoSuchKey returns null from callable, which means empty Mono
        StepVerifier.create(service.loadThumbnail(UUID.randomUUID()))
                .verifyComplete();
    }

    @Test
    void loadThumbnail_otherError_returnsEmpty() throws Exception {
        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        // Other exceptions are caught and return null (empty Mono)
        StepVerifier.create(service.loadThumbnail(UUID.randomUUID()))
                .verifyComplete();
    }

    @Test
    void deleteThumbnail_success_completes() throws Exception {
        doNothing().when(minioClient).removeObject(any(RemoveObjectArgs.class));

        StepVerifier.create(service.deleteThumbnail(UUID.randomUUID()))
                .verifyComplete();

        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void deleteThumbnail_noSuchKey_completesWithoutError() throws Exception {
        ErrorResponse errorResponse = mock(ErrorResponse.class);
        when(errorResponse.code()).thenReturn("NoSuchKey");
        ErrorResponseException ex = mock(ErrorResponseException.class);
        when(ex.errorResponse()).thenReturn(errorResponse);

        doThrow(ex).when(minioClient).removeObject(any(RemoveObjectArgs.class));

        StepVerifier.create(service.deleteThumbnail(UUID.randomUUID()))
                .verifyComplete();
    }

    @Test
    void deleteThumbnail_otherErrorResponseException_completesGracefully() throws Exception {
        ErrorResponse errorResponse = mock(ErrorResponse.class);
        when(errorResponse.code()).thenReturn("InternalError");
        ErrorResponseException ex = mock(ErrorResponseException.class);
        when(ex.errorResponse()).thenReturn(errorResponse);

        doThrow(ex).when(minioClient).removeObject(any(RemoveObjectArgs.class));

        StepVerifier.create(service.deleteThumbnail(UUID.randomUUID()))
                .verifyComplete();
    }

    @Test
    void deleteThumbnail_genericException_completesGracefully() throws Exception {
        doThrow(new RuntimeException("Unexpected error"))
                .when(minioClient).removeObject(any(RemoveObjectArgs.class));

        StepVerifier.create(service.deleteThumbnail(UUID.randomUUID()))
                .verifyComplete();
    }

    @Test
    void thumbnailExists_whenExists_returnsTrue() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenReturn(mock(StatObjectResponse.class));

        StepVerifier.create(service.thumbnailExists(UUID.randomUUID()))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void thumbnailExists_noSuchKey_returnsFalse() throws Exception {
        ErrorResponse errorResponse = mock(ErrorResponse.class);
        when(errorResponse.code()).thenReturn("NoSuchKey");
        ErrorResponseException ex = mock(ErrorResponseException.class);
        when(ex.errorResponse()).thenReturn(errorResponse);

        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(ex);

        StepVerifier.create(service.thumbnailExists(UUID.randomUUID()))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void thumbnailExists_otherError_returnsFalse() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(new RuntimeException("Connection failed"));

        StepVerifier.create(service.thumbnailExists(UUID.randomUUID()))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void copyThumbnail_whenSourceExists_copiesSuccessfully() throws Exception {
        // Source exists (statObject succeeds)
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenReturn(mock(StatObjectResponse.class));
        when(minioClient.copyObject(any(CopyObjectArgs.class)))
                .thenReturn(mock(ObjectWriteResponse.class));

        StepVerifier.create(service.copyThumbnail(UUID.randomUUID(), UUID.randomUUID()))
                .verifyComplete();

        verify(minioClient).copyObject(any(CopyObjectArgs.class));
    }

    @Test
    void copyThumbnail_whenSourceNotExists_skipsCopy() throws Exception {
        ErrorResponse errorResponse = mock(ErrorResponse.class);
        when(errorResponse.code()).thenReturn("NoSuchKey");
        ErrorResponseException ex = mock(ErrorResponseException.class);
        when(ex.errorResponse()).thenReturn(errorResponse);

        // Source does not exist
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(ex);

        StepVerifier.create(service.copyThumbnail(UUID.randomUUID(), UUID.randomUUID()))
                .verifyComplete();

        verify(minioClient, never()).copyObject(any(CopyObjectArgs.class));
    }

    @Test
    void initWithNonMainStorage_usesCustomConfig() {
        ThumbnailProperties props = new ThumbnailProperties();
        props.getStorage().setUseMainStorage(false);
        props.getStorage().getMinio().setEndpoint("http://custom:9000");
        props.getStorage().getMinio().setAccessKey("customKey");
        props.getStorage().getMinio().setSecretKey("customSecret");
        props.getStorage().getMinio().setBucketName("custom-thumbnails");

        MinioThumbnailStorageService customService =
                new MinioThumbnailStorageService(props, minioProperties, Optional.empty());
        // Just verify the constructor doesn't throw
        assertNotNull(customService);
    }

    @Test
    void initWithNonMainStorage_nullCustomConfig_fallsBackToMain() {
        ThumbnailProperties props = new ThumbnailProperties();
        props.getStorage().setUseMainStorage(false);
        // Leave minio config with null values - should fall back to main minioProperties

        MinioThumbnailStorageService customService =
                new MinioThumbnailStorageService(props, minioProperties, Optional.empty());
        assertNotNull(customService);
    }

    @Test
    void loadThumbnail_errorResponseException_nonNoSuchKey_propagatesError() throws Exception {
        ErrorResponse errorResponse = new ErrorResponse(
                "InternalError", "Internal server error", null, null, null, null, null);
        ErrorResponseException ex = new ErrorResponseException(errorResponse, null, null);

        when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(ex);

        // Non-NoSuchKey ErrorResponseException is re-thrown and propagates as error
        StepVerifier.create(service.loadThumbnail(UUID.randomUUID()))
                .expectError(ErrorResponseException.class)
                .verify();
    }

    @Test
    void thumbnailExists_errorResponseException_nonNoSuchKey_returnsFalse() throws Exception {
        ErrorResponse errorResponse = new ErrorResponse(
                "AccessDenied", "Access denied", null, null, null, null, null);
        ErrorResponseException ex = new ErrorResponseException(errorResponse, null, null);

        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(ex);

        StepVerifier.create(service.thumbnailExists(UUID.randomUUID()))
                .expectNext(false)
                .verifyComplete();
    }
}
