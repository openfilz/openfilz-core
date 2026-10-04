package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.service.quota.StorageQuotaService;
import org.openfilz.dms.config.TusProperties;
import org.openfilz.dms.dto.TusUploadMetadata;
import org.openfilz.dms.dto.request.TusFinalizeRequest;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.exception.DocumentNotFoundException;
import org.openfilz.dms.exception.FileSizeExceededException;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.utils.JsonUtils;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TusUploadServiceImplTest {

    @Mock private TusProperties tusProperties;
    @Mock private StorageQuotaService storageQuotaService;
    @Mock private StorageService storageService;
    @Mock private DocumentDAO documentDAO;
    @Mock private AuditService auditService;
    @Mock private JsonUtils jsonUtils;
    @Mock private MetadataPostProcessor metadataPostProcessor;
    @Mock private TransactionalOperator tx;
    @Mock private ObjectMapper objectMapper;
    @Mock private org.springframework.beans.factory.ObjectProvider<org.openfilz.dms.service.ChecksumService> checksumServiceProvider;
    @Mock private org.openfilz.dms.service.DocumentIntegrityService documentIntegrityService;

    private TusUploadServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TusUploadServiceImpl(tusProperties, storageQuotaService, storageService,
                documentDAO, auditService, jsonUtils, metadataPostProcessor, tx, objectMapper, checksumServiceProvider, documentIntegrityService);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseHeader(String header) {
        return (Map<String, String>) ReflectionTestUtils.invokeMethod(service, "parseMetadataHeader", header);
    }

    @Test
    void parseMetadataHeader_null_returnsEmptyMap() {
        assertTrue(parseHeader(null).isEmpty());
        assertTrue(parseHeader("   ").isEmpty());
    }

    @Test
    void parseMetadataHeader_decodesBase64Pairs() {
        String b64 = Base64.getEncoder().encodeToString("report.pdf".getBytes());
        Map<String, String> result = parseHeader("filename " + b64);
        assertEquals("report.pdf", result.get("filename"));
    }

    @Test
    void parseMetadataHeader_keyWithoutValue_storesEmptyString() {
        Map<String, String> result = parseHeader("is_confidential");
        assertEquals("", result.get("is_confidential"));
    }

    @Test
    void parseMetadataHeader_invalidBase64_isSkipped() {
        // "!!!!" is not valid base64 -> the pair is dropped, no exception bubbles up.
        Map<String, String> result = parseHeader("filename !!!!");
        assertFalse(result.containsKey("filename"));
    }

    @Test
    void getContentType_knownExtension_returnsMappedType() {
        assertEquals("application/pdf",
                ReflectionTestUtils.invokeMethod(service, "getContentType", "report.pdf"));
    }

    @Test
    void getContentType_unknownExtensionOrNoDot_returnsOctetStream() {
        assertEquals("application/octet-stream",
                ReflectionTestUtils.invokeMethod(service, "getContentType", "archive.unknownext"));
        assertEquals("application/octet-stream",
                ReflectionTestUtils.invokeMethod(service, "getContentType", "noextension"));
    }

    @Test
    void validateUploadCreation_quotasPass_completes() {
        when(storageQuotaService.checkUpload("upload", 100L)).thenReturn(Mono.empty());

        // null filename -> "upload" fallback; null parent + allowDuplicates -> no DB checks.
        StepVerifier.create(service.validateUploadCreation(100L, null, null, true))
                .verifyComplete();
        verifyNoInteractions(documentDAO);
    }

    @Test
    void validateUploadCreation_quotaRefused_errors() {
        when(storageQuotaService.checkUpload("big.bin", 100L))
                .thenReturn(Mono.error(new FileSizeExceededException("big.bin", 100L, 10L)));

        StepVerifier.create(service.validateUploadCreation(100L, "big.bin", null, true))
                .expectError(FileSizeExceededException.class)
                .verify();
    }

    @Test
    void limitTo_passesChunksWithinTheDeclaredLength() {
        var factory = new org.springframework.core.io.buffer.DefaultDataBufferFactory();
        reactor.core.publisher.Flux<org.springframework.core.io.buffer.DataBuffer> data = reactor.core.publisher.Flux.just(
                factory.wrap(new byte[4]), factory.wrap(new byte[6]));

        StepVerifier.create(TusUploadServiceImpl.limitTo(data, 10, 10)).expectNextCount(2).verifyComplete();
    }

    @Test
    void limitTo_failsOnceTheDeclaredLengthIsExceeded() {
        var factory = new org.springframework.core.io.buffer.DefaultDataBufferFactory();
        reactor.core.publisher.Flux<org.springframework.core.io.buffer.DataBuffer> data = reactor.core.publisher.Flux.just(
                factory.wrap(new byte[4]), factory.wrap(new byte[7]));

        StepVerifier.create(TusUploadServiceImpl.limitTo(data, 10, 10))
                .expectNextCount(1)
                .expectError(org.openfilz.dms.exception.TusUploadLengthExceededException.class)
                .verify();
    }

    // ------------------------------------------------------------ finalize is safe to ask again

    private static final String ID = "upload-1";
    private static final String DATA = "_tus/upload-1.bin";
    private static final String META = "_tus/upload-1.json";
    private static final String FINAL = "final#movie.bin";
    private static final TusFinalizeRequest REQUEST = new TusFinalizeRequest("movie.bin", null, null, false);

    /** A complete upload of {@code length} bytes, the caller's or {@code owner}'s. */
    private static TusUploadMetadata upload(long length, String owner, String finalStoragePath) {
        return new TusUploadMetadata(ID, length, length, Instant.now(), Instant.now().plusSeconds(3600), Map.of(), owner, finalStoragePath);
    }

    /** The server knows this upload, and everything a finalize needs around the move answers. */
    private void known(TusUploadMetadata meta) {
        lenient().when(storageService.getTusDataPath(ID)).thenReturn(DATA);
        lenient().when(storageService.getTusMetadataPath(ID)).thenReturn(META);
        lenient().when(storageService.getUniqueStorageFileName("movie.bin")).thenReturn(FINAL);
        lenient().doReturn(Mono.just(new ByteArrayResource("{}".getBytes()))).when(storageService).loadFile(META);
        lenient().when(objectMapper.readValue(any(InputStream.class), eq(TusUploadMetadata.class))).thenReturn(meta);
        lenient().when(objectMapper.writeValueAsBytes(any())).thenReturn("{}".getBytes());
        lenient().when(storageService.saveData(eq(META), any())).thenReturn(Mono.empty());
        lenient().when(storageService.deleteFile(anyString())).thenReturn(Mono.empty());
        lenient().when(storageService.listFiles(anyString())).thenReturn(reactor.core.publisher.Flux.empty());
        lenient().when(storageService.getLatestVersionId(anyString())).thenReturn(Mono.empty());
        lenient().when(storageQuotaService.checkUpload(eq("movie.bin"), anyLong())).thenReturn(Mono.empty());
        lenient().when(documentDAO.existsByNameAndParentId("movie.bin", null)).thenReturn(Mono.just(false));
        lenient().when(documentDAO.create(any())).thenAnswer(call -> {
            Document document = call.getArgument(0);
            document.setId(UUID.randomUUID());
            return Mono.just(document);
        });
        lenient().when(auditService.logAction(any(), any(), any(), any())).thenReturn(Mono.empty());
        lenient().when(tx.transactional(org.mockito.ArgumentMatchers.<Mono<Document>>any())).thenAnswer(call -> call.getArgument(0));
    }

    private Document created() {
        var document = org.mockito.ArgumentCaptor.forClass(Document.class);
        verify(documentDAO).create(document.capture());
        return document.getValue();
    }

    @Test
    void finalize_writesDownWhereTheFileGoesBeforeMovingIt() {
        known(upload(100, UserInfoService.ANONYMOUS_USER, null));
        when(storageService.moveFile(DATA, FINAL)).thenReturn(Mono.empty());
        when(storageService.getFileLength(FINAL)).thenReturn(Mono.just(100L));

        StepVerifier.create(service.finalizeUpload(ID, REQUEST)).expectNextCount(1).verifyComplete();

        var order = inOrder(storageService);
        order.verify(storageService).saveData(eq(META), any());
        order.verify(storageService).moveFile(DATA, FINAL);
        assertEquals(FINAL, created().getStoragePath());
    }

    /** The finalize before this one moved the file and was cut (a timeout, a restart): nothing is moved twice. */
    @Test
    void finalize_afterOneCutAfterTheMove_usesTheFileAlreadyMoved() {
        known(upload(100, UserInfoService.ANONYMOUS_USER, FINAL));
        when(documentDAO.existsByStoragePath(FINAL)).thenReturn(Mono.just(false));
        when(storageService.getFileLength(FINAL)).thenReturn(Mono.just(100L));

        StepVerifier.create(service.finalizeUpload(ID, REQUEST)).expectNextCount(1).verifyComplete();

        verify(storageService, never()).moveFile(anyString(), anyString());
        assertEquals(FINAL, created().getStoragePath());
    }

    @Test
    void finalize_afterOneCutBeforeTheMove_movesToTheSamePlace() {
        known(upload(100, UserInfoService.ANONYMOUS_USER, FINAL));
        when(documentDAO.existsByStoragePath(FINAL)).thenReturn(Mono.just(false));
        when(storageService.getFileLength(FINAL))
                .thenReturn(Mono.error(new org.openfilz.dms.exception.StorageException(new java.nio.file.NoSuchFileException(FINAL))))
                .thenReturn(Mono.just(100L));
        when(storageService.moveFile(DATA, FINAL)).thenReturn(Mono.empty());

        StepVerifier.create(service.finalizeUpload(ID, REQUEST)).expectNextCount(1).verifyComplete();

        verify(storageService).moveFile(DATA, FINAL);
        assertEquals(FINAL, created().getStoragePath());
    }

    /**
     * What S3 does when the pieces are gone: the move "succeeds" on an empty object. No document may
     * stand on it — the caller is told to send the file again (410).
     */
    @Test
    void finalize_whenWhatWasMovedIsNotTheAnnouncedLength_createsNoDocument() {
        known(upload(100, UserInfoService.ANONYMOUS_USER, null));
        when(storageService.moveFile(DATA, FINAL)).thenReturn(Mono.empty());
        when(storageService.getFileLength(FINAL)).thenReturn(Mono.just(0L));

        StepVerifier.create(service.finalizeUpload(ID, REQUEST))
                .expectErrorSatisfies(e -> assertEquals(HttpStatus.GONE, ((ResponseStatusException) e).getStatusCode()))
                .verify();

        verify(documentDAO, never()).create(any());
        verify(storageService).deleteFile(FINAL);
    }

    /**
     * The storage does not answer (down, timing out) when asked whether the moved file is there. That is
     * not "nothing there": the file is neither moved again — on S3 an empty object would overwrite it —
     * nor deleted. The finalize fails and is asked again.
     */
    @Test
    void finalize_whenTheStorageCannotSayWhatIsThere_touchesNothing() {
        known(upload(100, UserInfoService.ANONYMOUS_USER, FINAL));
        when(documentDAO.existsByStoragePath(FINAL)).thenReturn(Mono.just(false));
        when(storageService.getFileLength(FINAL))
                .thenReturn(Mono.error(new org.openfilz.dms.exception.StorageException("MinIO getFileLength failed", new java.net.SocketTimeoutException("timeout"))));

        StepVerifier.create(service.finalizeUpload(ID, REQUEST)).expectError(org.openfilz.dms.exception.StorageException.class).verify();

        verify(storageService, never()).moveFile(anyString(), anyString());
        verify(storageService, never()).deleteFile(FINAL);
        verify(documentDAO, never()).create(any());
    }

    @Test
    void finalize_whenTheStorageCannotSayAfterTheMove_keepsTheMovedFile() {
        known(upload(100, UserInfoService.ANONYMOUS_USER, null));
        when(storageService.moveFile(DATA, FINAL)).thenReturn(Mono.empty());
        when(storageService.getFileLength(FINAL))
                .thenReturn(Mono.error(new org.openfilz.dms.exception.StorageException("MinIO getFileLength failed", new java.net.SocketTimeoutException("timeout"))));

        StepVerifier.create(service.finalizeUpload(ID, REQUEST)).expectError(org.openfilz.dms.exception.StorageException.class).verify();

        verify(storageService, never()).deleteFile(FINAL);
        verify(documentDAO, never()).create(any());
    }

    /** The earlier finalize went all the way and only its clean-up is late: no second document on the same file. */
    @Test
    void finalize_ofAnUploadADocumentAlreadyHolds_isNotFound() {
        known(upload(100, UserInfoService.ANONYMOUS_USER, FINAL));
        when(documentDAO.existsByStoragePath(FINAL)).thenReturn(Mono.just(true));

        StepVerifier.create(service.finalizeUpload(ID, REQUEST)).expectError(DocumentNotFoundException.class).verify();

        verify(documentDAO, never()).create(any());
    }

    // ------------------------------------------------------------ cancel

    @Test
    void cancel_ofSomeoneElsesUpload_removesNothing() {
        known(upload(100, "someone@else.org", null));

        StepVerifier.create(service.cancelUpload(ID)).verifyComplete();

        verify(storageService, never()).deleteFile(anyString());
    }

    @Test
    void cancel_removesTheFileAnInterruptedFinalizeMoved() {
        known(upload(100, UserInfoService.ANONYMOUS_USER, FINAL));
        when(documentDAO.existsByStoragePath(FINAL)).thenReturn(Mono.just(false));

        StepVerifier.create(service.cancelUpload(ID)).verifyComplete();

        verify(storageService).deleteFile(FINAL);
        verify(storageService).deleteFile(META);
    }

    @Test
    void cancel_neverRemovesAFileADocumentHolds() {
        known(upload(100, UserInfoService.ANONYMOUS_USER, FINAL));
        when(documentDAO.existsByStoragePath(FINAL)).thenReturn(Mono.just(true));

        StepVerifier.create(service.cancelUpload(ID)).verifyComplete();

        verify(storageService, never()).deleteFile(FINAL);
        verify(storageService).deleteFile(META);
    }
}
