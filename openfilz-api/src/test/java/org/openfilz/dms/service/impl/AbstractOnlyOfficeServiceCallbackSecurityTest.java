package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.OnlyOfficeProperties;
import org.openfilz.dms.dto.request.OnlyOfficeCallbackRequest;
import org.openfilz.dms.dto.response.OnlyOfficeUserInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AccessType;
import org.openfilz.dms.exception.FileSizeExceededException;
import org.openfilz.dms.exception.OperationForbiddenException;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.security.OnlyOfficeAuthenticationToken;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.OnlyOfficeJwtExtractor;
import org.openfilz.dms.service.OnlyOfficeJwtService;
import org.openfilz.dms.utils.ContentInfo;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The save callback is reachable with any token signed with the shared secret, and users hold
 * one (the download access token in their editor config). It must therefore refuse a token for
 * another document, a download access token, and a download URL off the document server's host;
 * what it does download is capped.
 */
@ExtendWith(MockitoExtension.class)
class AbstractOnlyOfficeServiceCallbackSecurityTest {

    private static final UUID DOC_A = UUID.fromString("0c1c9d2a-4bd5-4a1a-9f0a-64c4bb7b58c2");
    private static final UUID DOC_B = UUID.fromString("f2c7a1de-2f4e-4a3e-9c0e-1d3a5b6c7d8e");
    private static final String RAW = "raw.jwt.token";

    @Mock private CommonProperties commonProperties;
    @Mock private OnlyOfficeProperties onlyOfficeProperties;
    @Mock private OnlyOfficeJwtService<OnlyOfficeUserInfo> jwtService;
    @Mock private OnlyOfficeJwtExtractor<OnlyOfficeUserInfo> jwtExtractor;
    @Mock private DocumentDAO documentDAO;
    @Mock private DocumentService documentService;

    private final OnlyOfficeProperties.DocumentServer documentServer = new OnlyOfficeProperties.DocumentServer();
    private final AtomicReference<String> requestedUrl = new AtomicReference<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>("hello");

    private AbstractOnlyOfficeService<OnlyOfficeUserInfo> service;

    @BeforeEach
    void setUp() {
        documentServer.setUrl("https://docs.example.com");
        lenient().when(onlyOfficeProperties.getDocumentServer()).thenReturn(documentServer);
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            requestedUrl.set(request.url().toString());
            return Mono.just(ClientResponse.create(HttpStatus.OK).body(responseBody.get()).build());
        });
        service = new AbstractOnlyOfficeService<>(commonProperties, onlyOfficeProperties, jwtService,
                jwtExtractor, documentDAO, documentService, builder);
    }

    private static OnlyOfficeCallbackRequest save(String url) {
        return new OnlyOfficeCallbackRequest(OnlyOfficeCallbackRequest.Status.READY_FOR_SAVE, DOC_A + "_1", url,
                List.of(), null, null, List.of(), null, null);
    }

    private static OnlyOfficeCallbackRequest saveWithBodyToken(String url, String token) {
        return new OnlyOfficeCallbackRequest(OnlyOfficeCallbackRequest.Status.READY_FOR_SAVE, DOC_A + "_1", url,
                List.of(), null, null, List.of(), null, token);
    }

    private static OnlyOfficeAuthenticationToken authFor(UUID documentId) {
        return new OnlyOfficeAuthenticationToken("user-1", "User One", "one@example.com", documentId, RAW);
    }

    // ---------------------------------------------------------------- URL allow-list

    @Test
    void allowedDownloadUrl_documentServerHost_accepted() {
        assertTrue(service.isAllowedDownloadUrl("https://docs.example.com/cache/files/key/output.docx"));
        assertTrue(service.isAllowedDownloadUrl("http://DOCS.example.com:8080/cache/files/key/output.docx"));
    }

    @Test
    void allowedDownloadUrl_foreignHost_refusedEvenIfPublic() {
        assertFalse(service.isAllowedDownloadUrl("http://opensearch:9200/_search?size=10000"));
        assertFalse(service.isAllowedDownloadUrl("https://example.org/anything"));
        assertFalse(service.isAllowedDownloadUrl("http://169.254.169.254/latest/meta-data/"));
    }

    @Test
    void allowedDownloadUrl_otherSchemesUserInfoAndGarbage_refused() {
        assertFalse(service.isAllowedDownloadUrl("ftp://docs.example.com/file"));
        assertFalse(service.isAllowedDownloadUrl("file:///etc/passwd"));
        assertFalse(service.isAllowedDownloadUrl("https://docs.example.com@evil.example.org/file"));
        assertFalse(service.isAllowedDownloadUrl("not a url"));
        assertFalse(service.isAllowedDownloadUrl(null));
    }

    @Test
    void allowedDownloadUrl_extraConfiguredHosts_accepted() {
        documentServer.setAllowedDownloadHosts(List.of("onlyoffice-ee", "http://documentserver.internal:80"));
        assertTrue(service.isAllowedDownloadUrl("http://onlyoffice-ee/cache/files/key/output.docx"));
        assertTrue(service.isAllowedDownloadUrl("http://documentserver.internal/cache/files/key/output.docx"));
        assertFalse(service.isAllowedDownloadUrl("http://documentserver.evil/cache/files/key/output.docx"));
    }

    @Test
    void allowedDownloadUrl_loopbackNamesAreOneHost() {
        documentServer.setUrl("http://localhost");
        assertTrue(service.isAllowedDownloadUrl("http://127.0.0.1:12345/cache/files/x"));
        assertTrue(service.isAllowedDownloadUrl("http://[::1]:12345/cache/files/x"));
        assertTrue(service.isAllowedDownloadUrl("http://localhost:12345/cache/files/x"));
        assertFalse(service.isAllowedDownloadUrl("http://opensearch:9200/"));
    }

    // ---------------------------------------------------------------- token binding

    @Test
    @DisplayName("a token bound to document B replayed on the callback of document A is refused")
    void callback_tokenForAnotherDocument_refused() {
        StepVerifier.create(service.handleCallback(DOC_A, save("https://docs.example.com/cache/files/k/output.docx"))
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authFor(DOC_B))))
                .expectError(OperationForbiddenException.class)
                .verify();

        verify(documentDAO, never()).findById(any(), any());
        verify(documentService, never()).replaceDocumentContent(any(), any(), any());
    }

    @Test
    @DisplayName("a download access token (type=access) cannot drive a callback, even for its own document")
    void callback_accessToken_refused() {
        when(jwtService.validateAndDecode(RAW)).thenReturn(Map.of("type", "access", "documentId", DOC_A.toString()));

        StepVerifier.create(service.handleCallback(DOC_A, save("https://docs.example.com/cache/files/k/output.docx"))
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authFor(DOC_A))))
                .expectError(OperationForbiddenException.class)
                .verify();

        verify(documentDAO, never()).findById(any(), any());
    }

    @Test
    @DisplayName("no OnlyOffice token in the security context: refused (fail closed)")
    void callback_noToken_refused() {
        StepVerifier.create(service.handleCallback(DOC_A, save("https://docs.example.com/cache/files/k/output.docx")))
                .expectError(OperationForbiddenException.class)
                .verify();
    }

    @Test
    @DisplayName("a body token that does not verify is refused")
    void callback_invalidBodyToken_refused() {
        when(jwtService.validateAndDecode(RAW)).thenReturn(Map.of("payload", Map.of("key", DOC_A + "_1")));
        when(jwtService.validateAndDecode("forged")).thenReturn(null);

        StepVerifier.create(service.handleCallback(DOC_A, saveWithBodyToken("https://docs.example.com/cache/files/k/output.docx", "forged"))
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authFor(DOC_A))))
                .expectError(OperationForbiddenException.class)
                .verify();

        verify(documentDAO, never()).findById(any(), any());
    }

    @Test
    @DisplayName("the download URL must be the one the document server signed")
    void callback_urlDiffersFromSignedPayload_refused() {
        when(jwtService.validateAndDecode(RAW)).thenReturn(Map.of("payload",
                Map.of("key", DOC_A + "_1", "url", "https://docs.example.com/cache/files/k/output.docx")));

        StepVerifier.create(service.handleCallback(DOC_A, save("https://docs.example.com/cache/files/other/output.docx"))
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authFor(DOC_A))))
                .expectError(OperationForbiddenException.class)
                .verify();
    }

    // ---------------------------------------------------------------- download

    @Test
    @DisplayName("a valid callback whose URL is on a foreign host is refused before any download")
    void callback_foreignHost_refused() {
        when(jwtService.validateAndDecode(RAW)).thenReturn(Map.of("payload", Map.of("key", DOC_A + "_1")));

        StepVerifier.create(service.handleCallback(DOC_A, save("http://opensearch:9200/_search?size=10000"))
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authFor(DOC_A))))
                .expectError(OperationForbiddenException.class)
                .verify();

        assertThat(requestedUrl.get()).isNull();
        verify(documentDAO, never()).findById(any(), any());
        verify(documentService, never()).replaceDocumentContent(any(), any(), any());
    }

    @Test
    @DisplayName("a valid callback on the document server's host downloads the file and replaces the content")
    void callback_configuredHost_downloadsAndSaves() {
        when(jwtService.validateAndDecode(RAW)).thenReturn(Map.of("payload", Map.of("key", DOC_A + "_1")));
        Document document = document();
        when(documentDAO.findById(DOC_A, AccessType.RW)).thenReturn(Mono.just(document));
        when(documentService.replaceDocumentContent(eq(DOC_A), any(), any())).thenReturn(Mono.just(document));

        StepVerifier.create(service.handleCallback(DOC_A, save("https://docs.example.com/cache/files/k/output.docx"))
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authFor(DOC_A))))
                .verifyComplete();

        assertThat(requestedUrl.get()).isEqualTo("https://docs.example.com/cache/files/k/output.docx");
        ArgumentCaptor<ContentInfo> info = ArgumentCaptor.forClass(ContentInfo.class);
        verify(documentService).replaceDocumentContent(eq(DOC_A), any(), info.capture());
        assertThat(info.getValue().length()).isEqualTo(5L);
    }

    @Test
    @DisplayName("a response above the download cap is refused and nothing is saved")
    void callback_responseAboveCap_refused() {
        documentServer.setMaxDownloadBytes(3);
        when(jwtService.validateAndDecode(RAW)).thenReturn(Map.of("payload", Map.of("key", DOC_A + "_1")));
        when(documentDAO.findById(DOC_A, AccessType.RW)).thenReturn(Mono.just(document()));

        StepVerifier.create(service.handleCallback(DOC_A, save("https://docs.example.com/cache/files/k/output.docx"))
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authFor(DOC_A))))
                .expectError(FileSizeExceededException.class)
                .verify();

        verify(documentService, never()).replaceDocumentContent(any(), any(), any());
    }

    @Test
    @DisplayName("a non-save status still needs a token bound to the document")
    void callback_editingStatus_tokenForAnotherDocument_refused() {
        OnlyOfficeCallbackRequest editing = new OnlyOfficeCallbackRequest(OnlyOfficeCallbackRequest.Status.EDITING,
                DOC_A + "_1", null, List.of(), null, null, List.of(), null, null);

        StepVerifier.create(service.handleCallback(DOC_A, editing)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authFor(DOC_B))))
                .expectError(OperationForbiddenException.class)
                .verify();
    }

    private static Document document() {
        return Document.builder().id(DOC_A).name("report.docx").build();
    }
}
