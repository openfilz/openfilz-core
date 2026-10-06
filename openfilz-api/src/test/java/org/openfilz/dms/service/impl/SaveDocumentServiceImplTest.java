package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.exception.FileSizeExceededException;
import org.openfilz.dms.exception.UserQuotaExceededException;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.quota.StorageQuotaService;
import org.openfilz.dms.utils.ContentInfo;
import org.openfilz.dms.utils.JsonUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The quotas are enforced on the stored length, whatever the client declared: a multipart part's
 * Content-Length is written by the client and used to be trusted as "already checked".
 */
@ExtendWith(MockitoExtension.class)
class SaveDocumentServiceImplTest {

    private static final long ONE_MB = 1024L * 1024;
    private static final String PATH = "stored/object";

    @Mock private StorageService storageService;
    @Mock private ObjectMapper objectMapper;
    @Mock private AuditService auditService;
    @Mock private JsonUtils jsonUtils;
    @Mock private DocumentDAO documentDAO;
    @Mock private MetadataPostProcessor metadataPostProcessor;
    @Mock private TransactionalOperator tx;
    @Mock private StorageQuotaService storageQuotaService;
    @Mock private FilePart filePart;

    private SaveDocumentServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new SaveDocumentServiceImpl(storageService, objectMapper, auditService, jsonUtils,
                documentDAO, metadataPostProcessor, tx, storageQuotaService);
        lenient().when(tx.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(filePart.filename()).thenReturn("big.bin");
        lenient().when(filePart.headers()).thenReturn(new HttpHeaders());
        lenient().when(storageService.getFileLength(PATH)).thenReturn(Mono.just(ONE_MB));
    }

    @Test
    @DisplayName("declared 10 bytes, 1 MiB stored: the per-file limit is checked on 1 MiB and the object is removed")
    void declaredLengthIsNotTrusted() {
        when(storageQuotaService.checkFileSize("big.bin", ONE_MB)).thenReturn(Mono.error(new FileSizeExceededException("big.bin", ONE_MB, 1024)));
        when(storageService.deleteFile(PATH)).thenReturn(Mono.empty());

        StepVerifier.create(service.doSaveFile(filePart, 10L, null, Map.of(), "big.bin", Mono.just(PATH)))
                .expectError(FileSizeExceededException.class)
                .verify();

        verify(storageService).deleteFile(PATH);
        verify(documentDAO, never()).create(any());
    }

    @Test
    @DisplayName("no declared length: the quotas are checked on the stored length")
    void missingDeclaredLengthIsChecked() {
        when(storageQuotaService.checkFileSize("big.bin", ONE_MB)).thenReturn(Mono.empty());
        when(storageQuotaService.checkStorage(ONE_MB)).thenReturn(Mono.error(new UserQuotaExceededException("alice", 0, ONE_MB, 10)));
        when(storageService.deleteFile(PATH)).thenReturn(Mono.empty());

        StepVerifier.create(service.doSaveFile(filePart, null, null, Map.of(), "big.bin", Mono.just(PATH)))
                .expectError(UserQuotaExceededException.class)
                .verify();

        verify(storageService).deleteFile(PATH);
    }

    @Test
    @DisplayName("an exact declared length was already checked before storage: no second check, document saved")
    void exactDeclaredLengthIsNotCheckedTwice() {
        Document saved = Document.builder().id(UUID.randomUUID()).name("big.bin").type(DocumentType.FILE)
                .size(ONE_MB).storagePath(PATH).contentType("application/octet-stream").build();
        when(documentDAO.create(any())).thenReturn(Mono.just(saved));
        when(storageService.getLatestVersionId(PATH)).thenReturn(Mono.empty());
        when(auditService.logAction(any(), any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.doSaveFile(filePart, ONE_MB, null, Map.of(), "big.bin", Mono.just(PATH)))
                .assertNext(response -> org.assertj.core.api.Assertions.assertThat(response.size()).isEqualTo(ONE_MB))
                .verifyComplete();

        verify(storageQuotaService, never()).checkFileSize(anyString(), anyLong());
        verify(storageQuotaService, never()).checkStorage(anyLong());
        verify(storageService, never()).deleteFile(anyString());
    }

    @Test
    @DisplayName("replacing in place (bucket versioning) over quota removes only the version just written")
    void inPlaceReplaceOverQuotaDropsTheNewVersion() {
        Document document = Document.builder().id(UUID.randomUUID()).name("big.bin").type(DocumentType.FILE)
                .size(100L).storagePath(PATH).build();
        when(storageQuotaService.checkFileSize("big.bin", ONE_MB)).thenReturn(Mono.empty());
        when(storageQuotaService.checkStorage(ONE_MB - 100)).thenReturn(Mono.error(new UserQuotaExceededException("alice", 0, ONE_MB, 10)));
        when(storageService.deleteLatestVersion(PATH)).thenReturn(Mono.empty());

        StepVerifier.create(service.replaceFileContentAndSave(filePart, new ContentInfo(10L, null), document, PATH, PATH))
                .expectError(UserQuotaExceededException.class)
                .verify();

        verify(storageService).deleteLatestVersion(PATH);
        verify(storageService, never()).deleteFile(anyString());
    }

    @Test
    @DisplayName("replacing with a new object over quota removes that new object")
    void newObjectReplaceOverQuotaDeletesIt() {
        Document document = Document.builder().id(UUID.randomUUID()).name("big.bin").type(DocumentType.FILE)
                .size(100L).storagePath("old/object").build();
        when(storageQuotaService.checkFileSize("big.bin", ONE_MB)).thenReturn(Mono.error(new FileSizeExceededException("big.bin", ONE_MB, 1024)));
        when(storageService.deleteFile(PATH)).thenReturn(Mono.empty());

        StepVerifier.create(service.replaceFileContentAndSave(filePart, new ContentInfo(10L, null), document, PATH, "old/object"))
                .expectError(FileSizeExceededException.class)
                .verify();

        verify(storageService).deleteFile(PATH);
        verify(storageService, never()).deleteFile(eq("old/object"));
    }
}
