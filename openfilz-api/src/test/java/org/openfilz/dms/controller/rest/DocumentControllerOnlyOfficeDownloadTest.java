package org.openfilz.dms.controller.rest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.dto.response.DownloadableVersion;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.DocumentVersionService;
import org.openfilz.dms.service.OnlyOfficeJwtService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DocumentController#downloadForOnlyOffice} — the token-validation,
 * version-streaming and service-unavailable branches not reached by the OAuth2 happy-path tests.
 */
@ExtendWith(MockitoExtension.class)
class DocumentControllerOnlyOfficeDownloadTest {

    @Mock
    private DocumentService documentService;
    @Mock
    private OnlyOfficeJwtService<?> onlyOfficeJwtService;
    @Mock
    private DocumentVersionService documentVersionService;

    private DocumentController controller;
    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new DocumentController(documentService, mock(ObjectMapper.class));
        ReflectionTestUtils.setField(controller, "onlyOfficeJwtService", onlyOfficeJwtService);
        ReflectionTestUtils.setField(controller, "documentVersionService", documentVersionService);
    }

    @Test
    void downloadForOnlyOffice_whenServiceDisabled_returnsServiceUnavailable() {
        ReflectionTestUtils.setField(controller, "onlyOfficeJwtService", null);

        StepVerifier.create(controller.downloadForOnlyOffice(documentId, "tok", null))
                .expectNextMatches(r -> r.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE)
                .verifyComplete();
    }

    @Test
    void downloadForOnlyOffice_invalidToken_returnsUnauthorized() {
        when(onlyOfficeJwtService.validateAndDecode("tok")).thenReturn(null);

        StepVerifier.create(controller.downloadForOnlyOffice(documentId, "tok", null))
                .expectNextMatches(r -> r.getStatusCode() == HttpStatus.UNAUTHORIZED)
                .verifyComplete();
    }

    @Test
    void downloadForOnlyOffice_documentIdMismatch_returnsUnauthorized() {
        when(onlyOfficeJwtService.validateAndDecode("tok")).thenReturn(Map.of("k", "v"));
        when(onlyOfficeJwtService.extractDocumentId(anyMap())).thenReturn(UUID.randomUUID());

        StepVerifier.create(controller.downloadForOnlyOffice(documentId, "tok", null))
                .expectNextMatches(r -> r.getStatusCode() == HttpStatus.UNAUTHORIZED)
                .verifyComplete();
    }

    @Test
    void downloadForOnlyOffice_versionRequestedButVersioningDisabled_returnsServiceUnavailable() {
        when(onlyOfficeJwtService.validateAndDecode("tok")).thenReturn(Map.of("k", "v"));
        when(onlyOfficeJwtService.extractDocumentId(anyMap())).thenReturn(documentId);
        ReflectionTestUtils.setField(controller, "documentVersionService", null);

        StepVerifier.create(controller.downloadForOnlyOffice(documentId, "tok", "v1"))
                .expectNextMatches(r -> r.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE)
                .verifyComplete();
    }

    @Test
    void downloadForOnlyOffice_versionRequested_streamsVersion() {
        when(onlyOfficeJwtService.validateAndDecode("tok")).thenReturn(Map.of("k", "v"));
        when(onlyOfficeJwtService.extractDocumentId(anyMap())).thenReturn(documentId);
        DownloadableVersion version = new DownloadableVersion("old.txt", "text/plain",
                new ByteArrayResource("data".getBytes()));
        when(documentVersionService.downloadVersion(documentId, "v1")).thenReturn(Mono.just(version));

        StepVerifier.create(controller.downloadForOnlyOffice(documentId, "tok", "v1"))
                .expectNextMatches(r -> r.getStatusCode() == HttpStatus.OK && r.getBody() != null)
                .verifyComplete();
    }

    @Test
    void downloadForOnlyOffice_latestVersion_streamsCurrentDocument() {
        when(onlyOfficeJwtService.validateAndDecode("tok")).thenReturn(Map.of("k", "v"));
        when(onlyOfficeJwtService.extractDocumentId(anyMap())).thenReturn(documentId);
        Document document = Document.builder()
                .id(documentId).name("doc.txt").type(DocumentType.FILE).contentType("text/plain").build();
        when(documentService.findDocumentToDownloadById(documentId)).thenReturn(Mono.just(document));
        doReturn(Mono.just(new ByteArrayResource("body".getBytes())))
                .when(documentService).downloadDocument(any(Document.class));

        StepVerifier.create(controller.downloadForOnlyOffice(documentId, "tok", null))
                .expectNextMatches(r -> r.getStatusCode() == HttpStatus.OK)
                .verifyComplete();
    }
}
