package org.openfilz.dms.service.impl;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import io.minio.messages.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.config.MinioProperties;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class MinioStorageServiceTest {

    @Mock
    private MinioProperties minioProperties;

    @Mock
    private MinioClient minioClient;

    private MinioStorageService service;

    @BeforeEach
    void setUp() {
        lenient().when(minioProperties.getBucketName()).thenReturn("test-bucket");

        service = new MinioStorageService(minioProperties);
        ReflectionTestUtils.setField(service, "minioClient", minioClient);
        ReflectionTestUtils.setField(service, "pipedBufferSize", 8192);
        ReflectionTestUtils.setField(service, "wormMode", false);
    }

    @Test
    void loadFile_success_returnsResource() throws Exception {
        GetObjectResponse mockResponse = mock(GetObjectResponse.class);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResponse);

        StepVerifier.create(service.loadFile("test-object"))
                .expectNextMatches(resource -> {
                    assertNotNull(resource);
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void loadFile_error_throwsException() throws Exception {
        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        StepVerifier.create(service.loadFile("test-object"))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void deleteFile_success_completes() throws Exception {
        doNothing().when(minioClient).removeObject(any(RemoveObjectArgs.class));

        StepVerifier.create(service.deleteFile("test-object"))
                .verifyComplete();

        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void deleteFile_noSuchKey_completesWithoutError() throws Exception {
        ErrorResponse errorResponse = mock(ErrorResponse.class);
        when(errorResponse.code()).thenReturn("NoSuchKey");
        ErrorResponseException ex = mock(ErrorResponseException.class);
        when(ex.errorResponse()).thenReturn(errorResponse);

        doThrow(ex).when(minioClient).removeObject(any(RemoveObjectArgs.class));

        StepVerifier.create(service.deleteFile("non-existent-object"))
                .verifyComplete();
    }

    @Test
    void deleteFile_otherError_throwsException() throws Exception {
        ErrorResponse errorResponse = mock(ErrorResponse.class);
        when(errorResponse.code()).thenReturn("InternalError");
        ErrorResponseException ex = mock(ErrorResponseException.class);
        when(ex.errorResponse()).thenReturn(errorResponse);

        doThrow(ex).when(minioClient).removeObject(any(RemoveObjectArgs.class));

        StepVerifier.create(service.deleteFile("error-object"))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void copyFile_success_returnsNewPath() throws Exception {
        when(minioClient.copyObject(any(CopyObjectArgs.class)))
                .thenReturn(mock(ObjectWriteResponse.class));

        StepVerifier.create(service.copyFile("source/file.txt"))
                .expectNextMatches(path -> {
                    assertNotNull(path);
                    assertTrue(path.contains("file.txt"));
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void copyFile_error_throwsException() throws Exception {
        when(minioClient.copyObject(any(CopyObjectArgs.class)))
                .thenThrow(new RuntimeException("Copy failed"));

        StepVerifier.create(service.copyFile("source/file.txt"))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void getFileLength_success_returnsSize() throws Exception {
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(12345L);
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(stat);

        StepVerifier.create(service.getFileLength("test-object"))
                .expectNext(12345L)
                .verifyComplete();
    }

    @Test
    void getFileLength_error_throwsStorageException() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(new RuntimeException("Stat failed"));

        StepVerifier.create(service.getFileLength("test-object"))
                .expectError()
                .verify();
    }

    @Test
    void createEmptyFile_returnsEmpty() {
        StepVerifier.create(service.createEmptyFile("_tus/upload.bin"))
                .verifyComplete();
    }

    @Test
    void deleteLatestVersion_whenVersioningDisabled_returnsEmpty() {
        when(minioProperties.isVersioningEnabled()).thenReturn(false);

        StepVerifier.create(service.deleteLatestVersion("object"))
                .verifyComplete();

        verifyNoInteractions(minioClient);
    }

    @Test
    void deleteLatestVersion_whenVersioningEnabled_deletesVersion() throws Exception {
        when(minioProperties.isVersioningEnabled()).thenReturn(true);

        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.versionId()).thenReturn("version-123");
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(stat);
        doNothing().when(minioClient).removeObject(any(RemoveObjectArgs.class));

        StepVerifier.create(service.deleteLatestVersion("object"))
                .verifyComplete();

        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void deleteLatestVersion_whenNoVersionId_doesNotDelete() throws Exception {
        when(minioProperties.isVersioningEnabled()).thenReturn(true);

        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.versionId()).thenReturn(null);
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(stat);

        StepVerifier.create(service.deleteLatestVersion("object"))
                .verifyComplete();

        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void deleteLatestVersion_whenError_completesGracefully() throws Exception {
        when(minioProperties.isVersioningEnabled()).thenReturn(true);
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(new RuntimeException("Stat failed"));

        StepVerifier.create(service.deleteLatestVersion("object"))
                .verifyComplete();
    }

    // Note: replaceFile, saveFile, and uploadToObject are not tested here because
    // they involve complex PipedInputStream/PipedOutputStream interactions with FilePart.
    // These are covered by integration tests.

    @Test
    void listFiles_success_returnsFileNames() throws Exception {
        // listFiles uses Flux.create with minioClient.listObjects
        Item item1 = mock(Item.class);
        when(item1.objectName()).thenReturn("prefix/file1.txt");
        Item item2 = mock(Item.class);
        when(item2.objectName()).thenReturn("prefix/file2.txt");

        Result<Item> result1 = mock(Result.class);
        when(result1.get()).thenReturn(item1);
        Result<Item> result2 = mock(Result.class);
        when(result2.get()).thenReturn(item2);

        Iterable<Result<Item>> results = java.util.List.of(result1, result2);
        when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(results);

        StepVerifier.create(service.listFiles("prefix/"))
                .expectNext("prefix/file1.txt")
                .expectNext("prefix/file2.txt")
                .verifyComplete();
    }

    @Test
    void listFiles_error_returnsStorageException() {
        when(minioClient.listObjects(any(ListObjectsArgs.class)))
                .thenThrow(new RuntimeException("List failed"));

        StepVerifier.create(service.listFiles("prefix/"))
                .expectError()
                .verify();
    }
}
