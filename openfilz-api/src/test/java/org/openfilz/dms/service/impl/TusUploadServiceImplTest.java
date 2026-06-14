package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.config.QuotaProperties;
import org.openfilz.dms.config.TusProperties;
import org.openfilz.dms.exception.FileSizeExceededException;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.utils.JsonUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TusUploadServiceImplTest {

    @Mock private TusProperties tusProperties;
    @Mock private QuotaProperties quotaProperties;
    @Mock private StorageService storageService;
    @Mock private DocumentDAO documentDAO;
    @Mock private AuditService auditService;
    @Mock private JsonUtils jsonUtils;
    @Mock private MetadataPostProcessor metadataPostProcessor;
    @Mock private TransactionalOperator tx;
    @Mock private ObjectMapper objectMapper;

    private TusUploadServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TusUploadServiceImpl(tusProperties, quotaProperties, storageService,
                documentDAO, auditService, jsonUtils, metadataPostProcessor, tx, objectMapper);
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
    void validateUploadCreation_allQuotasDisabled_completes() {
        when(quotaProperties.isFileUploadQuotaEnabled()).thenReturn(false);
        when(quotaProperties.isUserQuotaEnabled()).thenReturn(false);

        // null filename -> "upload" fallback; null parent + allowDuplicates -> no DB checks.
        StepVerifier.create(service.validateUploadCreation(100L, null, null, true))
                .verifyComplete();
        verifyNoInteractions(documentDAO);
    }

    @Test
    void validateFileUploadQuota_overLimit_errors() {
        when(quotaProperties.isFileUploadQuotaEnabled()).thenReturn(true);
        when(quotaProperties.getFileUploadQuotaInBytes()).thenReturn(10L);

        Mono<Void> result = ReflectionTestUtils.invokeMethod(service, "validateFileUploadQuota", 100L, "big.bin");

        StepVerifier.create(result)
                .expectError(FileSizeExceededException.class)
                .verify();
    }
}
