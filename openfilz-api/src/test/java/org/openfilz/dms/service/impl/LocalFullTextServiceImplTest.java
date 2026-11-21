package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.service.IndexService;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.utils.BoundedTaskQueue;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** Real queue: the text-extraction pipeline runs through it (field-injected in the app). */
    private final BoundedTaskQueue postProcessingQueue = new BoundedTaskQueue("test", 2);

    @BeforeEach
    void injectQueue() {
        ReflectionTestUtils.setField(service, "postProcessingQueue", postProcessingQueue);
    }

    @AfterEach
    void stopQueue() {
        postProcessingQueue.dispose();
    }

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
                .contentType("application/pdf")
                .size(1024L)
                .storagePath("storage/doc.pdf")
                .build();

        doReturn(Mono.just(new ByteArrayResource("content".getBytes())))
                .when(storageService).loadFile("storage/doc.pdf");
        when(indexService.indexDocMetadataMono(doc)).thenReturn(Mono.empty());
        when(tikaService.processResource(any(), any(), any())).thenReturn(Flux.empty());
        when(indexService.indexDocumentStream(any(), eq(doc.getId()))).thenReturn(Mono.empty());

        service.indexDocument(doc);

        verify(storageService, timeout(2000)).loadFile("storage/doc.pdf");
        verify(tikaService, timeout(2000)).processResource(any(), any(), any());
        verify(indexService, timeout(2000)).indexDocumentStream(any(), eq(doc.getId()));
    }

    @Test
    void indexDocument_withNonExtractableContentType_skipsTextExtraction() {
        Document doc = Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FILE)
                .name("logo.svg")
                .contentType("image/svg+xml")
                .size(2048L)
                .storagePath("storage/logo.svg")
                .build();

        when(indexService.indexDocMetadataMono(doc)).thenReturn(Mono.empty());

        service.indexDocument(doc);

        verify(indexService, timeout(2000)).indexDocMetadataMono(doc);
        verifyNoInteractions(tikaService);
        verifyNoInteractions(storageService);
    }

    @Test
    void indexDocument_withImageContentType_skipsTextExtraction() {
        Document doc = Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FILE)
                .name("photo.jpg")
                .contentType("image/jpeg")
                .size(5000L)
                .storagePath("storage/photo.jpg")
                .build();

        when(indexService.indexDocMetadataMono(doc)).thenReturn(Mono.empty());

        service.indexDocument(doc);

        verify(indexService, timeout(2000)).indexDocMetadataMono(doc);
        verifyNoInteractions(tikaService);
    }

    @Test
    void indexDocument_withNullContentType_skipsTextExtraction() {
        Document doc = Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FILE)
                .name("unknown.bin")
                .size(100L)
                .storagePath("storage/unknown.bin")
                .build();

        when(indexService.indexDocMetadataMono(doc)).thenReturn(Mono.empty());

        service.indexDocument(doc);

        verify(indexService, timeout(2000)).indexDocMetadataMono(doc);
        verifyNoInteractions(tikaService);
    }

    @Test
    void indexDocument_withDocxContentType_callsFullTextIndexing() {
        Document doc = Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FILE)
                .name("report.docx")
                .contentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .size(3000L)
                .storagePath("storage/report.docx")
                .build();

        doReturn(Mono.just(new ByteArrayResource("content".getBytes())))
                .when(storageService).loadFile("storage/report.docx");
        when(indexService.indexDocMetadataMono(doc)).thenReturn(Mono.empty());
        when(tikaService.processResource(any(), any(), any())).thenReturn(Flux.empty());
        when(indexService.indexDocumentStream(any(), eq(doc.getId()))).thenReturn(Mono.empty());

        service.indexDocument(doc);

        verify(tikaService, timeout(2000)).processResource(any(), any(), any());
        // Last step of the queued pipeline: wait for it, or strict stubs may see it unused (race)
        verify(indexService, timeout(2000)).indexDocumentStream(any(), eq(doc.getId()));
    }

    @Test
    void indexDocument_withPlainTextContentType_callsFullTextIndexing() {
        Document doc = Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FILE)
                .name("notes.txt")
                .contentType("text/plain")
                .size(500L)
                .storagePath("storage/notes.txt")
                .build();

        doReturn(Mono.just(new ByteArrayResource("content".getBytes())))
                .when(storageService).loadFile("storage/notes.txt");
        when(indexService.indexDocMetadataMono(doc)).thenReturn(Mono.empty());
        when(tikaService.processResource(any(), any(), any())).thenReturn(Flux.empty());
        when(indexService.indexDocumentStream(any(), eq(doc.getId()))).thenReturn(Mono.empty());

        service.indexDocument(doc);

        verify(tikaService, timeout(2000)).processResource(any(), any(), any());
        verify(indexService, timeout(2000)).indexDocumentStream(any(), eq(doc.getId()));
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

    @Test
    void isRetryableException_conflictMessages_areRetryable() {
        assertEquals(Boolean.TRUE, org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "isRetryableException", new RuntimeException("version_conflict")));
        assertEquals(Boolean.TRUE, org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "isRetryableException", new RuntimeException("HTTP 409 Conflict")));
    }

    @Test
    void isRetryableException_otherOrNullMessage_notRetryable() {
        assertEquals(Boolean.FALSE, org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "isRetryableException", new RuntimeException("boom")));
        assertEquals(Boolean.FALSE, org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "isRetryableException", new RuntimeException()));
    }
    @Test
    void indexDocument_retryAfterConflict_skipsDocumentDeletedMeanwhile() {
        Document doc = extractableFile();
        doReturn(Mono.just(new ByteArrayResource("content".getBytes())))
                .when(storageService).loadFile("storage/doc.pdf");
        when(indexService.indexDocMetadataMono(doc)).thenReturn(Mono.empty());
        when(tikaService.processResource(any(), any(), any())).thenReturn(Flux.empty());
        when(indexService.indexDocumentStream(any(), eq(doc.getId())))
                .thenReturn(Mono.error(new RuntimeException("version_conflict")));
        // The delete removed the index entry between the first attempt and the retry.
        when(indexService.exists(doc.getId())).thenReturn(Mono.just(false));

        service.indexDocument(doc);

        verify(indexService, timeout(2000)).exists(doc.getId());
        // Only the first attempt indexed the metadata: the retry must not re-create the entry.
        verify(indexService, after(500).times(1)).indexDocMetadataMono(doc);
    }

    @Test
    void indexDocument_retryAfterConflict_reindexesDocumentStillIndexed() {
        Document doc = extractableFile();
        doReturn(Mono.just(new ByteArrayResource("content".getBytes())))
                .when(storageService).loadFile("storage/doc.pdf");
        when(indexService.indexDocMetadataMono(doc)).thenReturn(Mono.empty());
        when(tikaService.processResource(any(), any(), any())).thenReturn(Flux.empty());
        when(indexService.indexDocumentStream(any(), eq(doc.getId())))
                .thenReturn(Mono.error(new RuntimeException("version_conflict")), Mono.empty());
        when(indexService.exists(doc.getId())).thenReturn(Mono.just(true));

        service.indexDocument(doc);

        verify(indexService, timeout(2000).times(2)).indexDocMetadataMono(doc);
    }

    private static Document extractableFile() {
        return Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FILE)
                .name("document.pdf")
                .contentType("application/pdf")
                .size(1024L)
                .storagePath("storage/doc.pdf")
                .build();
    }

    private Document pdf() {
        return Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FILE)
                .name("contract.pdf")
                .contentType("application/pdf")
                .size(1024L)
                .storagePath("storage/contract.pdf")
                .build();
    }

    @Test
    void textExtractedEvent_publishedAfterIndexing_withCharCount() {
        org.springframework.context.ApplicationEventPublisher events = mock(org.springframework.context.ApplicationEventPublisher.class);
        ReflectionTestUtils.setField(service, "events", events);
        Document doc = pdf();
        doReturn(Mono.just(new ByteArrayResource("content".getBytes()))).when(storageService).loadFile(doc.getStoragePath());
        when(indexService.indexDocMetadataMono(doc)).thenReturn(Mono.empty());
        when(tikaService.processResource(any(), any(), any())).thenReturn(Flux.just("hello ", "world"));
        // The index consumes the stream, as OpenSearchIndexService does
        when(indexService.indexDocumentStream(any(), eq(doc.getId())))
                .thenAnswer(inv -> inv.<Flux<String>>getArgument(0).then());

        service.indexDocument(doc);

        org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(events, timeout(2000)).publishEvent(captor.capture());
        org.openfilz.dms.event.DocumentTextExtractedEvent event = (org.openfilz.dms.event.DocumentTextExtractedEvent) captor.getValue();
        assertEquals(doc.getId(), event.documentId());
        assertEquals("contract.pdf", event.name());
        assertEquals(11, event.indexedChars());
        assertEquals(false, event.truncated());
    }

    @Test
    void textExtractedEvent_truncatedCountsTheSpaceTheIndexAddsBetweenFragments() {
        org.springframework.context.ApplicationEventPublisher events = mock(org.springframework.context.ApplicationEventPublisher.class);
        ReflectionTestUtils.setField(service, "events", events);
        Document doc = pdf();
        doReturn(Mono.just(new ByteArrayResource("content".getBytes()))).when(storageService).loadFile(doc.getStoragePath());
        when(indexService.indexDocMetadataMono(doc)).thenReturn(Mono.empty());
        // Two halves of the cap: the index joins them with a space, which pushes the last character out
        String half = "a".repeat(OpenSearchIndexService.MAX_INDEXED_CONTENT_LENGTH / 2);
        when(tikaService.processResource(any(), any(), any())).thenReturn(Flux.just(half, half));
        when(indexService.indexDocumentStream(any(), eq(doc.getId())))
                .thenAnswer(inv -> inv.<Flux<String>>getArgument(0).then());

        service.indexDocument(doc);

        org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(events, timeout(2000)).publishEvent(captor.capture());
        org.openfilz.dms.event.DocumentTextExtractedEvent event = (org.openfilz.dms.event.DocumentTextExtractedEvent) captor.getValue();
        assertEquals(OpenSearchIndexService.MAX_INDEXED_CONTENT_LENGTH, event.indexedChars());
        assertEquals(true, event.truncated());
    }

    @Test
    void textExtractedEvent_notPublishedWhenIndexingFails() {
        org.springframework.context.ApplicationEventPublisher events = mock(org.springframework.context.ApplicationEventPublisher.class);
        ReflectionTestUtils.setField(service, "events", events);
        Document doc = pdf();
        doReturn(Mono.just(new ByteArrayResource("content".getBytes()))).when(storageService).loadFile(doc.getStoragePath());
        when(indexService.indexDocMetadataMono(doc)).thenReturn(Mono.empty());
        when(tikaService.processResource(any(), any(), any())).thenReturn(Flux.just("text"));
        when(indexService.indexDocumentStream(any(), eq(doc.getId()))).thenReturn(Mono.error(new IllegalStateException("index down")));
        lenient().when(indexService.exists(doc.getId())).thenReturn(Mono.just(false));

        service.indexDocument(doc);

        verify(indexService, timeout(2000)).indexDocumentStream(any(), eq(doc.getId()));
        verify(events, after(1500).never()).publishEvent(any(Object.class));
    }

    @Test
    void textExtractedEvent_listenerErrorDoesNotFailIndexing() {
        org.springframework.context.ApplicationEventPublisher events = mock(org.springframework.context.ApplicationEventPublisher.class);
        doThrow(new IllegalStateException("listener broke")).when(events).publishEvent(any(Object.class));
        ReflectionTestUtils.setField(service, "events", events);
        Document doc = pdf();
        doReturn(Mono.just(new ByteArrayResource("content".getBytes()))).when(storageService).loadFile(doc.getStoragePath());
        when(indexService.indexDocMetadataMono(doc)).thenReturn(Mono.empty());
        when(tikaService.processResource(any(), any(), any())).thenReturn(Flux.just("text"));
        when(indexService.indexDocumentStream(any(), eq(doc.getId()))).thenReturn(Mono.empty());

        service.indexDocument(doc);

        verify(events, timeout(2000)).publishEvent(any(Object.class));
        // No retry: a retry would ask the index whether the document still exists
        verify(indexService, after(1000).never()).exists(doc.getId());
        verify(indexService, times(1)).indexDocumentStream(any(), eq(doc.getId()));
    }
}
