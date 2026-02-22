package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.service.IndexService;
import org.openfilz.dms.service.StorageService;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LocalFullTextServiceImplTest {

    @Mock
    private IndexService indexService;

    @Mock
    private TikaService tikaService;

    @Mock
    private StorageService storageService;

    @InjectMocks
    private LocalFullTextServiceImpl service;

    @Test
    void indexDocument_withFolder_callsIndexDocMetadata() {
        Document doc = Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FOLDER)
                .name("test-folder")
                .build();

        when(indexService.indexDocMetadataMono(doc)).thenReturn(Mono.empty());

        service.indexDocument(doc);

        verify(indexService, timeout(2000)).indexDocMetadataMono(doc);
    }

    @Test
    void indexDocument_withEmptyFile_callsIndexDocMetadataOnly() {
        Document doc = Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FILE)
                .name("empty.txt")
                .size(0L)
                .build();

        when(indexService.indexDocMetadataMono(doc)).thenReturn(Mono.empty());

        service.indexDocument(doc);

        verify(indexService, timeout(2000)).indexDocMetadataMono(doc);
        verifyNoInteractions(tikaService);
    }

    @Test
    void indexDocumentMetadata_callsUpdateMetadata() {
        Document doc = Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FILE)
                .name("test.txt")
                .build();

        when(indexService.updateMetadata(doc)).thenReturn(Mono.empty());

        service.indexDocumentMetadata(doc);

        verify(indexService, timeout(2000)).updateMetadata(doc);
    }

    @Test
    void copyIndex_callsCopyIndex() {
        UUID sourceId = UUID.randomUUID();
        Document createdDoc = Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FILE)
                .name("copy.txt")
                .build();

        when(indexService.copyIndex(sourceId, createdDoc)).thenReturn(Mono.empty());

        service.copyIndex(sourceId, createdDoc);

        verify(indexService, timeout(2000)).copyIndex(sourceId, createdDoc);
    }

    @Test
    void updateIndexField_withDocument_callsUpdateIndexField() {
        Document doc = Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FILE)
                .name("test.txt")
                .build();

        when(indexService.updateIndexField(doc, "name", "newName")).thenReturn(Mono.empty());

        service.updateIndexField(doc, "name", "newName");

        verify(indexService, timeout(2000)).updateIndexField(doc, "name", "newName");
    }

    @Test
    void updateIndexField_withDocumentId_callsUpdateIndexField() {
        UUID docId = UUID.randomUUID();

        when(indexService.updateIndexField(docId, "name", "newName")).thenReturn(Mono.empty());

        service.updateIndexField(docId, "name", "newName");

        verify(indexService, timeout(2000)).updateIndexField(docId, "name", "newName");
    }

    @Test
    void deleteDocument_callsDeleteDocument() {
        UUID docId = UUID.randomUUID();

        when(indexService.deleteDocument(docId)).thenReturn(Mono.empty());

        service.deleteDocument(docId);

        verify(indexService, timeout(2000)).deleteDocument(docId);
    }

    @Test
    void indexDocument_withFolder_handlesErrorGracefully() {
        Document doc = Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FOLDER)
                .name("error-folder")
                .build();

        when(indexService.indexDocMetadataMono(doc))
                .thenReturn(Mono.error(new RuntimeException("some error")));

        // Should not throw - errors are handled with doOnError and logged
        service.indexDocument(doc);

        verify(indexService, timeout(2000)).indexDocMetadataMono(doc);
    }

    @Test
    void deleteDocument_handlesErrorGracefully() {
        UUID docId = UUID.randomUUID();

        when(indexService.deleteDocument(docId))
                .thenReturn(Mono.error(new RuntimeException("delete error")));

        // Should not throw
        service.deleteDocument(docId);

        verify(indexService, timeout(2000)).deleteDocument(docId);
    }

    @Test
    void indexDocumentMetadata_handlesErrorGracefully() {
        Document doc = Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FILE)
                .name("error-doc.txt")
                .build();

        when(indexService.updateMetadata(doc))
                .thenReturn(Mono.error(new RuntimeException("metadata error")));

        // Should not throw
        service.indexDocumentMetadata(doc);

        verify(indexService, timeout(2000)).updateMetadata(doc);
    }

    @Test
    void indexDocument_withFileAndPositiveSize_callsFullTextIndexing() {
        Document doc = Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FILE)
                .name("document.pdf")
                .size(1024L)
                .storagePath("storage/doc.pdf")
                .build();

        doReturn(Mono.just(new ByteArrayResource("content".getBytes())))
                .when(storageService).loadFile("storage/doc.pdf");
        when(indexService.indexDocMetadataMono(doc)).thenReturn(Mono.empty());
        when(tikaService.processResource(any(), any())).thenReturn(Flux.empty());
        when(indexService.indexDocumentStream(any(), eq(doc.getId()))).thenReturn(Mono.empty());

        service.indexDocument(doc);

        verify(storageService, timeout(2000)).loadFile("storage/doc.pdf");
        verify(tikaService, timeout(2000)).processResource(any(), any());
    }

    @Test
    void copyIndex_handlesErrorGracefully() {
        UUID sourceId = UUID.randomUUID();
        Document doc = Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FILE)
                .name("copy.txt")
                .build();

        when(indexService.copyIndex(sourceId, doc))
                .thenReturn(Mono.error(new RuntimeException("copy error")));

        // Should not throw
        service.copyIndex(sourceId, doc);

        verify(indexService, timeout(2000)).copyIndex(sourceId, doc);
    }

    @Test
    void updateIndexField_withDocument_handlesErrorGracefully() {
        Document doc = Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FILE)
                .name("test.txt")
                .build();

        when(indexService.updateIndexField(doc, "name", "newName"))
                .thenReturn(Mono.error(new RuntimeException("update error")));

        // Should not throw
        service.updateIndexField(doc, "name", "newName");

        verify(indexService, timeout(2000)).updateIndexField(doc, "name", "newName");
    }

    @Test
    void updateIndexField_withId_handlesErrorGracefully() {
        UUID docId = UUID.randomUUID();

        when(indexService.updateIndexField(docId, "name", "newName"))
                .thenReturn(Mono.error(new RuntimeException("update error")));

        // Should not throw
        service.updateIndexField(docId, "name", "newName");

        verify(indexService, timeout(2000)).updateIndexField(docId, "name", "newName");
    }
}
