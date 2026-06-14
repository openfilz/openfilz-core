package org.openfilz.dms.service.impl;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import io.minio.messages.Item;
import io.minio.messages.VersioningConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.config.MinioProperties;
import org.openfilz.dms.exception.StorageException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
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

    // ==================== Upload / replace ====================

    private static FilePart filePart(String name, String content) {
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        DataBuffer buffer = factory.wrap(content.getBytes());
        FilePart filePart = mock(FilePart.class);
        lenient().when(filePart.filename()).thenReturn(name);
        lenient().when(filePart.headers()).thenReturn(new HttpHeaders());
        lenient().when(filePart.content()).thenReturn(Flux.just(buffer));
        return filePart;
    }

    @Test
    void saveFile_uploadsAndReturnsUniqueObjectName() throws Exception {
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(mock(ObjectWriteResponse.class));

        StepVerifier.create(service.saveFile(filePart("doc.txt", "hello world")))
                .expectNextMatches(name -> name.endsWith("doc.txt") && name.contains("#"))
                .verifyComplete();

        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    void replaceFile_whenVersioningDisabled_savesAsNewObject() throws Exception {
        when(minioProperties.isVersioningEnabled()).thenReturn(false);
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(mock(ObjectWriteResponse.class));

        StepVerifier.create(service.replaceFile("old#doc.txt", filePart("doc.txt", "data")))
                .expectNextMatches(name -> name.endsWith("doc.txt"))
                .verifyComplete();
    }

    @Test
    void replaceFile_whenVersioningEnabled_overwritesSameObject() throws Exception {
        when(minioProperties.isVersioningEnabled()).thenReturn(true);
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(mock(ObjectWriteResponse.class));

        StepVerifier.create(service.replaceFile("same#doc.txt", filePart("doc.txt", "data")))
                .expectNext("same#doc.txt")
                .verifyComplete();
    }

    // ==================== TUS chunk support ====================

    private static Flux<DataBuffer> dataFlux(String content) {
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        return Flux.just(factory.wrap(content.getBytes()));
    }

    @Test
    void appendData_storesChunkAndReturnsNewOffset() throws Exception {
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(mock(ObjectWriteResponse.class));
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(5L);
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(stat);

        StepVerifier.create(service.appendData("_tus/u1.bin", dataFlux("hello"), 10L))
                .expectNext(15L)
                .verifyComplete();
    }

    @Test
    void saveData_success_completes() throws Exception {
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(mock(ObjectWriteResponse.class));

        StepVerifier.create(service.saveData("_tus/u1.bin", dataFlux("payload")))
                .verifyComplete();

        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    void saveData_putObjectFails_throwsStorageException() throws Exception {
        when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(new RuntimeException("boom"));

        StepVerifier.create(service.saveData("_tus/u1.bin", dataFlux("payload")))
                .expectError(StorageException.class)
                .verify();
    }

    @Test
    void moveFile_composesChunksAndDeletesThem() throws Exception {
        Item item1 = mock(Item.class);
        when(item1.objectName()).thenReturn("_tus/u1.bin.chunk.00000000000000000000");
        Result<Item> r1 = mock(Result.class);
        when(r1.get()).thenReturn(item1);
        when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(java.util.List.of(r1));
        when(minioClient.composeObject(any(ComposeObjectArgs.class))).thenReturn(mock(ObjectWriteResponse.class));
        doNothing().when(minioClient).removeObject(any(RemoveObjectArgs.class));

        StepVerifier.create(service.moveFile("_tus/u1.bin", "final#doc.txt"))
                .verifyComplete();

        verify(minioClient).composeObject(any(ComposeObjectArgs.class));
        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void moveFile_chunkDeleteFailure_isSwallowed() throws Exception {
        Item item1 = mock(Item.class);
        when(item1.objectName()).thenReturn("_tus/u1.bin.chunk.00000000000000000000");
        Result<Item> r1 = mock(Result.class);
        when(r1.get()).thenReturn(item1);
        when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(java.util.List.of(r1));
        when(minioClient.composeObject(any(ComposeObjectArgs.class))).thenReturn(mock(ObjectWriteResponse.class));
        doThrow(new RuntimeException("cannot delete")).when(minioClient).removeObject(any(RemoveObjectArgs.class));

        StepVerifier.create(service.moveFile("_tus/u1.bin", "final#doc.txt"))
                .verifyComplete();
    }

    @Test
    void moveFile_noChunks_createsEmptyObject() throws Exception {
        when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(java.util.List.of());
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(mock(ObjectWriteResponse.class));

        StepVerifier.create(service.moveFile("_tus/empty.bin", "final#empty.txt"))
                .verifyComplete();

        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    void moveFile_listFails_throwsStorageException() throws Exception {
        when(minioClient.listObjects(any(ListObjectsArgs.class)))
                .thenThrow(new RuntimeException("list failed"));

        StepVerifier.create(service.moveFile("_tus/u1.bin", "final#doc.txt"))
                .expectError(StorageException.class)
                .verify();
    }

    // ==================== Versioning support ====================

    @Test
    void listFileVersions_disabled_returnsEmpty() {
        when(minioProperties.isVersioningEnabled()).thenReturn(false);

        StepVerifier.create(service.listFileVersions("obj"))
                .verifyComplete();
        verifyNoInteractions(minioClient);
    }

    @Test
    void listFileVersions_enabled_emitsVersionsAndSkipsDeleteMarkersAndOtherKeys() throws Exception {
        when(minioProperties.isVersioningEnabled()).thenReturn(true);

        Item match = mock(Item.class);
        when(match.objectName()).thenReturn("obj");
        when(match.isDeleteMarker()).thenReturn(false);
        when(match.versionId()).thenReturn("v1");
        when(match.lastModified()).thenReturn(null);
        when(match.size()).thenReturn(42L);
        when(match.isLatest()).thenReturn(true);
        Result<Item> rMatch = mock(Result.class);
        when(rMatch.get()).thenReturn(match);

        Item deleteMarker = mock(Item.class);
        when(deleteMarker.objectName()).thenReturn("obj");
        when(deleteMarker.isDeleteMarker()).thenReturn(true);
        Result<Item> rMarker = mock(Result.class);
        when(rMarker.get()).thenReturn(deleteMarker);

        Item other = mock(Item.class);
        when(other.objectName()).thenReturn("obj-other");
        Result<Item> rOther = mock(Result.class);
        when(rOther.get()).thenReturn(other);

        when(minioClient.listObjects(any(ListObjectsArgs.class)))
                .thenReturn(java.util.List.of(rMatch, rMarker, rOther));

        StepVerifier.create(service.listFileVersions("obj"))
                .expectNextMatches(v -> "v1".equals(v.versionId()) && v.size() == 42L)
                .verifyComplete();
    }

    @Test
    void listFileVersions_error_emitsStorageException() throws Exception {
        when(minioProperties.isVersioningEnabled()).thenReturn(true);
        when(minioClient.listObjects(any(ListObjectsArgs.class)))
                .thenThrow(new RuntimeException("list versions failed"));

        StepVerifier.create(service.listFileVersions("obj"))
                .expectError(StorageException.class)
                .verify();
    }

    @Test
    void loadFileVersion_success_returnsResource() throws Exception {
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mock(GetObjectResponse.class));

        StepVerifier.create(service.loadFileVersion("obj", "v1"))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void loadFileVersion_error_throwsStorageException() throws Exception {
        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenThrow(new RuntimeException("not found"));

        StepVerifier.create(service.loadFileVersion("obj", "v1"))
                .expectError(StorageException.class)
                .verify();
    }

    @Test
    void restoreFileVersion_success_returnsNewVersionId() throws Exception {
        ObjectWriteResponse response = mock(ObjectWriteResponse.class);
        when(response.versionId()).thenReturn("v-new");
        when(minioClient.copyObject(any(CopyObjectArgs.class))).thenReturn(response);

        StepVerifier.create(service.restoreFileVersion("obj", "v-old"))
                .expectNext("v-new")
                .verifyComplete();
    }

    @Test
    void restoreFileVersion_error_throwsStorageException() throws Exception {
        when(minioClient.copyObject(any(CopyObjectArgs.class)))
                .thenThrow(new RuntimeException("copy failed"));

        StepVerifier.create(service.restoreFileVersion("obj", "v-old"))
                .expectError(StorageException.class)
                .verify();
    }

    @Test
    void getLatestVersionId_disabled_returnsEmpty() {
        when(minioProperties.isVersioningEnabled()).thenReturn(false);

        StepVerifier.create(service.getLatestVersionId("obj"))
                .verifyComplete();
        verifyNoInteractions(minioClient);
    }

    @Test
    void getLatestVersionId_enabled_returnsVersionId() throws Exception {
        when(minioProperties.isVersioningEnabled()).thenReturn(true);
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.versionId()).thenReturn("v9");
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(stat);

        StepVerifier.create(service.getLatestVersionId("obj"))
                .expectNext("v9")
                .verifyComplete();
    }

    @Test
    void getLatestVersionId_statFails_resumesEmpty() throws Exception {
        when(minioProperties.isVersioningEnabled()).thenReturn(true);
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(new RuntimeException("stat failed"));

        StepVerifier.create(service.getLatestVersionId("obj"))
                .verifyComplete();
    }

    // ==================== Bucket initialization ====================

    @Test
    void ensureBucketExists_whenMissing_createsBucketAndEnablesVersioning() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        when(minioProperties.isVersioningEnabled()).thenReturn(true);

        ReflectionTestUtils.invokeMethod(service, "ensureBucketExists");

        verify(minioClient).makeBucket(any(MakeBucketArgs.class));
        verify(minioClient).setBucketVersioning(any(SetBucketVersioningArgs.class));
    }

    @Test
    void ensureBucketExists_whenExistsButVersioningSuspended_enablesVersioning() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(minioProperties.isVersioningEnabled()).thenReturn(true);
        VersioningConfiguration suspended =
                new VersioningConfiguration(VersioningConfiguration.Status.SUSPENDED, false);
        when(minioClient.getBucketVersioning(any(GetBucketVersioningArgs.class))).thenReturn(suspended);

        ReflectionTestUtils.invokeMethod(service, "ensureBucketExists");

        verify(minioClient).setBucketVersioning(any(SetBucketVersioningArgs.class));
    }

    @Test
    void ensureBucketExists_whenExistsAndWormMode_readsObjectLockConfiguration() throws Exception {
        ReflectionTestUtils.setField(service, "wormMode", true);
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(minioProperties.isVersioningEnabled()).thenReturn(false);
        when(minioClient.getObjectLockConfiguration(any(GetObjectLockConfigurationArgs.class)))
                .thenReturn(mock(io.minio.messages.ObjectLockConfiguration.class));

        ReflectionTestUtils.invokeMethod(service, "ensureBucketExists");

        verify(minioClient).getObjectLockConfiguration(any(GetObjectLockConfigurationArgs.class));
    }
}
