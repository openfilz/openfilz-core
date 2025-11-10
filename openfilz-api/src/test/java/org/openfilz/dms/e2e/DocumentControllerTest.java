package org.openfilz.dms.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.controller.rest.DocumentController;
import org.openfilz.dms.dto.request.DeleteMetadataRequest;
import org.openfilz.dms.dto.request.SearchMetadataRequest;
import org.openfilz.dms.dto.response.DocumentInfo;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.exception.DocumentNotFoundException;
import org.openfilz.dms.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentControllerTest {

    @Mock
    private DocumentService documentService;

    @InjectMocks
    private DocumentController documentController;

    @Mock
    private FilePart filePart;


    @BeforeEach
    void setUp() {
    }

    @Test
    void uploadDocument_Success() {
        UUID documentId = UUID.randomUUID();
        String filename = "test.txt";
        UUID parentId = UUID.randomUUID();

        when(documentService.uploadDocument(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new UploadResponse(documentId, filename, null, null)));

        StepVerifier.create(documentController.uploadDocument(filePart, parentId.toString(), null, 100L, false))
                .expectNextMatches(response ->
                    response.getStatusCode().is2xxSuccessful() &&
                    response.getBody().id().equals(documentId) &&
                    response.getBody().name().equals(filename)
                )
                .verifyComplete();
    }


    @Test
    void getDocumentMetadata_Success() {
        UUID documentId = UUID.randomUUID();
        Map<String, Object> metadata = Map.of("key1", "value1", "key2", "value2");
        SearchMetadataRequest request = new SearchMetadataRequest(null);

        when(documentService.getDocumentMetadata(documentId, request))
                .thenReturn(Mono.just(metadata));

        StepVerifier.create(documentController.getDocumentMetadata(documentId, request))
                .expectNextMatches(response ->
                    response.getStatusCode().is2xxSuccessful() &&
                            Objects.equals(response.getBody(), metadata)
                )
                .verifyComplete();
    }

    @Test
    void getDocumentInfo_Success() {
        UUID documentId = UUID.randomUUID();
        DocumentInfo info = new DocumentInfo(DocumentType.FILE, "test.txt", null, null, null);

        when(documentService.getDocumentInfo(documentId, false))
                .thenReturn(Mono.just(info));

        StepVerifier.create(documentController.getDocumentInfo(documentId, false))
                .expectNextMatches(response ->
                    response.getStatusCode().is2xxSuccessful() &&
                    Objects.requireNonNull(response.getBody()).type().equals(DocumentType.FILE) &&
                    response.getBody().name().equals("test.txt")
                )
                .verifyComplete();
    }

    @Test
    void deleteDocumentMetadata_Success() {
        UUID documentId = UUID.randomUUID();
        DeleteMetadataRequest request = new DeleteMetadataRequest(List.of("key1"));

        when(documentService.deleteDocumentMetadata(any(), any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(documentController.deleteDocumentMetadata(documentId, request))
                .expectNextMatches(response -> response.getStatusCode().equals(HttpStatus.NO_CONTENT))
                .verifyComplete();
    }

    @Test
    void downloadDocument_NotFound_ShouldReturnNotFound() {
        UUID documentId = UUID.randomUUID();

        when(documentService.findDocumentToDownloadById(documentId)).thenReturn(Mono.error(new DocumentNotFoundException(documentId)));

        StepVerifier.create(documentController.downloadDocument(documentId))
                .expectError(DocumentNotFoundException.class)
                .verify();
    }

    @Test
    void updateDocumentMetadata_InvalidRequest_ShouldReturnBadRequest() {
        UUID documentId = UUID.randomUUID();

        when(documentService.updateDocumentMetadata(any(), any()))
                .thenReturn(Mono.error(new IllegalArgumentException("Invalid metadata update request")));

        StepVerifier.create(documentController.updateDocumentMetadata(documentId, null))
                .expectErrorMatches(throwable ->
                        throwable instanceof IllegalArgumentException &&
                        throwable.getMessage().equals("Invalid metadata update request"))
                .verify();
    }
}