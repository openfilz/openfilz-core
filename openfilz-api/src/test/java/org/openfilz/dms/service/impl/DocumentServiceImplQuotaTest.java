package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.openfilz.dms.config.QuotaProperties;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.response.ChildElementInfo;
import org.openfilz.dms.exception.FileSizeExceededException;
import org.openfilz.dms.exception.OperationForbiddenException;
import org.openfilz.dms.exception.StorageException;
import org.openfilz.dms.exception.UserQuotaExceededException;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.*;
import org.openfilz.dms.utils.BlankDocumentGenerator;
import org.openfilz.dms.utils.JsonUtils;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the quota-validation helpers and the folder-name guard in {@link DocumentServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceImplQuotaTest {

    @Mock private TransactionalOperator tx;
    @Mock private StorageService storageService;
    @Mock private ObjectMapper objectMapper;
    @Mock private AuditService auditService;
    @Mock private JsonUtils jsonUtils;
    @Mock private DocumentDAO documentDAO;
    @Mock private SaveDocumentService saveDocumentService;
    @Mock private MetadataPostProcessor metadataPostProcessor;
    @Mock private DocumentDeleteService documentDeleteService;
    @Mock private BlankDocumentGenerator blankDocumentGenerator;
    @Mock private QuotaProperties quotaProperties;

    @InjectMocks
    private DocumentServiceImpl service;

    @Test
    void validateFileSize_quotaDisabled_completes() {
        when(quotaProperties.isFileUploadQuotaEnabled()).thenReturn(false);
        StepVerifier.create(service.validateFileSize(1000L, "f.bin")).verifyComplete();
    }

    @Test
    void validateFileSize_nullLength_completes() {
        when(quotaProperties.isFileUploadQuotaEnabled()).thenReturn(true);
        StepVerifier.create(service.validateFileSize(null, "f.bin")).verifyComplete();
    }

    @Test
    void validateFileSize_overLimit_errors() {
        when(quotaProperties.isFileUploadQuotaEnabled()).thenReturn(true);
        when(quotaProperties.getFileUploadQuotaInBytes()).thenReturn(10L);

        StepVerifier.create(service.validateFileSize(100L, "big.bin"))
                .expectError(FileSizeExceededException.class)
                .verify();
    }

    @Test
    void validateFileSize_underLimit_completes() {
        when(quotaProperties.isFileUploadQuotaEnabled()).thenReturn(true);
        when(quotaProperties.getFileUploadQuotaInBytes()).thenReturn(1000L);

        StepVerifier.create(service.validateFileSize(100L, "ok.bin")).verifyComplete();
    }

    @Test
    void validateUserQuota_disabled_completes() {
        when(quotaProperties.isUserQuotaEnabled()).thenReturn(false);
        StepVerifier.create(service.validateUserQuota(100L)).verifyComplete();
    }

    @Test
    void validateUserQuota_overQuota_errors() {
        // No security context -> getConnectedUserEmail() resolves to anonymousUser.
        when(quotaProperties.isUserQuotaEnabled()).thenReturn(true);
        when(quotaProperties.getUserQuotaInBytes()).thenReturn(10L);
        when(documentDAO.getTotalStorageByUser(anyString())).thenReturn(Mono.just(5L));

        StepVerifier.create(service.validateUserQuota(100L))
                .expectError(UserQuotaExceededException.class)
                .verify();
    }

    @Test
    void validateUserQuota_withinQuota_completes() {
        when(quotaProperties.isUserQuotaEnabled()).thenReturn(true);
        when(quotaProperties.getUserQuotaInBytes()).thenReturn(10_000L);
        when(documentDAO.getTotalStorageByUser(anyString())).thenReturn(Mono.just(5L));

        StepVerifier.create(service.validateUserQuota(100L)).verifyComplete();
    }

    @Test
    void validateQuotas_combinesFileAndUserChecks() {
        when(quotaProperties.isFileUploadQuotaEnabled()).thenReturn(false);
        when(quotaProperties.isUserQuotaEnabled()).thenReturn(false);

        StepVerifier.create(service.validateQuotas(100L, "f.bin")).verifyComplete();
    }

    @Test
    void createFolder_nameWithSlash_isForbidden() {
        StepVerifier.create(service.createFolder(new CreateFolderRequest("bad/name", null)))
                .expectError(OperationForbiddenException.class)
                .verify();
    }

    // ==================== validateUserQuotaForReplace (net-change based) ====================

    private Mono<Void> replaceQuota(Long newSize, Long oldSize) {
        return ReflectionTestUtils.invokeMethod(service, "validateUserQuotaForReplace", newSize, oldSize);
    }

    @Test
    void validateUserQuotaForReplace_disabled_completes() {
        when(quotaProperties.isUserQuotaEnabled()).thenReturn(false);
        StepVerifier.create(replaceQuota(1000L, 0L)).verifyComplete();
    }

    @Test
    void validateUserQuotaForReplace_smallerOrEqualFile_completes() {
        when(quotaProperties.isUserQuotaEnabled()).thenReturn(true);
        // net change <= 0 -> no quota concern
        StepVerifier.create(replaceQuota(50L, 100L)).verifyComplete();
    }

    @Test
    void validateUserQuotaForReplace_netIncreaseOverQuota_errors() {
        when(quotaProperties.isUserQuotaEnabled()).thenReturn(true);
        when(quotaProperties.getUserQuotaInBytes()).thenReturn(10L);
        when(documentDAO.getTotalStorageByUser(anyString())).thenReturn(Mono.just(5L));

        StepVerifier.create(replaceQuota(1000L, 0L))
                .expectError(UserQuotaExceededException.class)
                .verify();
    }

    @Test
    void validateUserQuotaForReplace_netIncreaseWithinQuota_completes() {
        when(quotaProperties.isUserQuotaEnabled()).thenReturn(true);
        when(quotaProperties.getUserQuotaInBytes()).thenReturn(1_000_000L);
        when(documentDAO.getTotalStorageByUser(anyString())).thenReturn(Mono.just(0L));

        StepVerifier.create(replaceQuota(1000L, 0L)).verifyComplete();
    }

    // ==================== ZIP helper methods ====================

    @Test
    void closeOutputStream_swallowsCloseException() throws Exception {
        ZipArchiveOutputStream zos = mock(ZipArchiveOutputStream.class);
        doThrow(new IOException("close fail")).when(zos).close();

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "closeOutputStream", zos));
    }

    @Test
    void deleteTempFile_removesExistingFile() throws Exception {
        Path tmp = Files.createTempFile("zip-helper", ".tmp");
        assertTrue(Files.exists(tmp));

        ReflectionTestUtils.invokeMethod(service, "deleteTempFile", tmp);

        assertFalse(Files.exists(tmp));
    }

    @Test
    void addFolderToZip_writesDirectoryEntry() throws Exception {
        ZipArchiveOutputStream zos = mock(ZipArchiveOutputStream.class);
        ChildElementInfo folder = ChildElementInfo.builder().path("docs").build();

        Mono<Boolean> result = ReflectionTestUtils.invokeMethod(service, "addFolderToZip", folder, zos);

        StepVerifier.create(result).expectNext(true).verifyComplete();
        verify(zos).putArchiveEntry(any(ZipArchiveEntry.class));
        verify(zos).closeArchiveEntry();
    }

    @Test
    void addFolderToZip_onIoError_emitsStorageException() throws Exception {
        ZipArchiveOutputStream zos = mock(ZipArchiveOutputStream.class);
        doThrow(new IOException("disk full")).when(zos).putArchiveEntry(any(ZipArchiveEntry.class));
        ChildElementInfo folder = ChildElementInfo.builder().path("docs").build();

        Mono<Boolean> result = ReflectionTestUtils.invokeMethod(service, "addFolderToZip", folder, zos);

        StepVerifier.create(result).expectError(StorageException.class).verify();
    }

    @Test
    void isDescendant_nullIds_false_andSameId_true() {
        java.util.UUID id = java.util.UUID.randomUUID();
        Mono<Boolean> nullCase = ReflectionTestUtils.invokeMethod(service, "isDescendant", null, id);
        StepVerifier.create(nullCase).expectNext(false).verifyComplete();

        Mono<Boolean> sameCase = ReflectionTestUtils.invokeMethod(service, "isDescendant", id, id);
        StepVerifier.create(sameCase).expectNext(true).verifyComplete();
    }

    @Test
    void addFileToZip_missingResource_returnsFalse() {
        org.openfilz.dms.entity.Document doc = org.openfilz.dms.entity.Document.builder()
                .name("missing.txt").storagePath("path/missing").size(10L).build();
        Resource resource = mock(Resource.class);
        when(resource.exists()).thenReturn(false);
        doReturn(Mono.just(resource)).when(storageService).loadFile("path/missing");

        Mono<Boolean> result = ReflectionTestUtils.invokeMethod(service, "addFileToZip", doc, "missing.txt",
                mock(ZipArchiveOutputStream.class));

        StepVerifier.create(result).expectNext(false).verifyComplete();
    }

    @Test
    void addFileToZip_writesEntry() throws Exception {
        org.openfilz.dms.entity.Document doc = org.openfilz.dms.entity.Document.builder()
                .name("a.txt").storagePath("path/a").size(4L).build();
        Resource resource = mock(Resource.class);
        when(resource.exists()).thenReturn(true);
        when(resource.getInputStream()).thenReturn(new java.io.ByteArrayInputStream("data".getBytes()));
        doReturn(Mono.just(resource)).when(storageService).loadFile("path/a");
        ZipArchiveOutputStream zos = mock(ZipArchiveOutputStream.class);

        Mono<Boolean> result = ReflectionTestUtils.invokeMethod(service, "addFileToZip", doc, "a.txt", zos);

        StepVerifier.create(result).expectNext(true).verifyComplete();
        verify(zos).putArchiveEntry(any(ZipArchiveEntry.class));
    }

    @Test
    void addFileToZip_ioError_emitsStorageException() throws Exception {
        org.openfilz.dms.entity.Document doc = org.openfilz.dms.entity.Document.builder()
                .name("a.txt").storagePath("path/a").size(4L).build();
        Resource resource = mock(Resource.class);
        when(resource.exists()).thenReturn(true);
        doReturn(Mono.just(resource)).when(storageService).loadFile("path/a");
        ZipArchiveOutputStream zos = mock(ZipArchiveOutputStream.class);
        doThrow(new IOException("zip fail")).when(zos).putArchiveEntry(any(ZipArchiveEntry.class));

        Mono<Boolean> result = ReflectionTestUtils.invokeMethod(service, "addFileToZip", doc, "a.txt", zos);

        StepVerifier.create(result).expectError(StorageException.class).verify();
    }

    @Test
    void addDocumentToZip_dispatchesByType() throws Exception {
        ZipArchiveOutputStream zos = mock(ZipArchiveOutputStream.class);
        // Folder element -> addFolderToZip
        ChildElementInfo folder = ChildElementInfo.builder()
                .type(org.openfilz.dms.enums.DocumentType.FOLDER).path("dir").build();
        Mono<Boolean> folderResult = ReflectionTestUtils.invokeMethod(service, "addDocumentToZip", folder, zos);
        StepVerifier.create(folderResult).expectNext(true).verifyComplete();

        // File element -> addFileToZip (missing resource -> false)
        ChildElementInfo file = ChildElementInfo.builder()
                .type(org.openfilz.dms.enums.DocumentType.FILE).path("dir/a.txt").storagePath("path/a").build();
        Resource resource = mock(Resource.class);
        when(resource.exists()).thenReturn(false);
        doReturn(Mono.just(resource)).when(storageService).loadFile("path/a");
        Mono<Boolean> fileResult = ReflectionTestUtils.invokeMethod(service, "addDocumentToZip", file, zos);
        StepVerifier.create(fileResult).expectNext(false).verifyComplete();
    }

    @Test
    void toCleanupResource_hidesFilenameAndDeletesOnClose() throws Exception {
        Path tmp = Files.createTempFile("cleanup", ".zip");
        Files.write(tmp, "zipdata".getBytes());

        Resource resource = ReflectionTestUtils.invokeMethod(service, "toCleanupResource", tmp);

        assertNotNull(resource);
        assertNull(resource.getFilename());
        try (InputStream is = resource.getInputStream()) {
            is.readAllBytes();
        }
        // closing the stream deletes the temp file
        assertFalse(Files.exists(tmp));
    }
}
