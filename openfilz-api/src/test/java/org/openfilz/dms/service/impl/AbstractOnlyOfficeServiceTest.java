package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.OnlyOfficeProperties;
import org.openfilz.dms.dto.response.OnlyOfficeUserInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.OnlyOfficeJwtExtractor;
import org.openfilz.dms.service.OnlyOfficeJwtService;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AbstractOnlyOfficeServiceTest {

    @Mock private CommonProperties commonProperties;
    @Mock private OnlyOfficeProperties onlyOfficeProperties;
    @Mock private OnlyOfficeJwtService<OnlyOfficeUserInfo> jwtService;
    @Mock private OnlyOfficeJwtExtractor<OnlyOfficeUserInfo> jwtExtractor;
    @Mock private DocumentDAO documentDAO;
    @Mock private DocumentService documentService;
    @Mock private WebClient.Builder webClientBuilder;

    private AbstractOnlyOfficeService<OnlyOfficeUserInfo> service;

    @BeforeEach
    void setUp() {
        service = new AbstractOnlyOfficeService<>(commonProperties, onlyOfficeProperties, jwtService,
                jwtExtractor, documentDAO, documentService, webClientBuilder);
    }

    @Test
    void isSupported_nullFilename_false() {
        assertFalse(service.isSupported(null));
    }

    @Test
    void isSupported_delegatesToPropertiesForExtension() {
        when(onlyOfficeProperties.isExtensionSupported("docx")).thenReturn(true);
        assertTrue(service.isSupported("report.docx"));
    }

    @Test
    void isEnabled_delegatesToProperties() {
        when(onlyOfficeProperties.isEnabled()).thenReturn(true);
        assertTrue(service.isEnabled());
    }

    @Test
    void isDocumentReadOnly_baseImplementation_neverRestricts() {
        assertFalse(service.isDocumentReadOnly(Document.builder().build()));
    }

    @Test
    void getFileExtension_variants() {
        assertEquals("pdf", ReflectionTestUtils.invokeMethod(service, "getFileExtension", "report.PDF"));
        assertEquals("", ReflectionTestUtils.invokeMethod(service, "getFileExtension", "noextension"));
    }

    @Test
    void getDocumentType_mapsExtensionToEditorType() {
        assertEquals("word", ReflectionTestUtils.invokeMethod(service, "getDocumentType", "a.docx"));
        assertEquals("cell", ReflectionTestUtils.invokeMethod(service, "getDocumentType", "a.xlsx"));
        assertEquals("slide", ReflectionTestUtils.invokeMethod(service, "getDocumentType", "a.pptx"));
        assertEquals("word", ReflectionTestUtils.invokeMethod(service, "getDocumentType", "a.pdf"));
        assertEquals("word", ReflectionTestUtils.invokeMethod(service, "getDocumentType", "a.unknown"));
    }

    @Test
    void generateDocumentKey_withVersionId_usesVersionScopedKey() {
        Document doc = Document.builder().id(UUID.randomUUID()).build();
        String key = ReflectionTestUtils.invokeMethod(service, "generateDocumentKey", doc, "v123");
        assertTrue(key.contains("_v_v123"));
    }

    @Test
    void generateDocumentKey_noVersion_fallsBackToCreatedAtWhenUpdatedAtNull() {
        Document doc = Document.builder()
                .id(UUID.randomUUID())
                .createdAt(OffsetDateTime.now())
                .updatedAt(null)
                .build();
        String key = ReflectionTestUtils.invokeMethod(service, "generateDocumentKey", doc, "");
        assertTrue(key.startsWith(doc.getId().toString() + "_"));
    }

    @Test
    void getFileExtension_nullFilename_returnsEmpty() throws Exception {
        var m = AbstractOnlyOfficeService.class.getDeclaredMethod("getFileExtension", String.class);
        m.setAccessible(true);
        assertEquals("", m.invoke(service, (Object) null));
    }

    @Test
    void closeQuietly_swallowsCloseException() throws Exception {
        java.io.OutputStream os = mock(java.io.OutputStream.class);
        doThrow(new java.io.IOException("close fail")).when(os).close();

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "closeQuietly", os));
    }

    @Test
    void buildDocumentUrl_withVersionId_appendsEncodedVersion() {
        when(commonProperties.getApiInternalBaseUrl()).thenReturn("http://api");
        UUID id = UUID.randomUUID();
        String url = ReflectionTestUtils.invokeMethod(service, "buildDocumentUrl", id, "tok", "v 1");
        assertTrue(url.contains("/api/v1/documents/" + id + "/onlyoffice-download?token=tok"));
        assertTrue(url.contains("versionId=v+1") || url.contains("versionId=v%201"));
    }
}
