package org.openfilz.dms.service.impl;

import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.IndexNameProvider;
import org.openfilz.dms.service.OpenSearchMetadataService;
import org.openfilz.dms.utils.JsonUtils;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch.core.DeleteRequest;
import org.opensearch.client.opensearch.core.GetResponse;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.UpdateRequest;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenSearchIndexServiceTest {

    @Mock
    private OpenSearchAsyncClient client;
    @Mock
    private IndexNameProvider indexNameProvider;
    @Mock
    private OpenSearchMetadataService metadataService;
    @Mock
    private JsonUtils jsonUtils;

    private OpenSearchIndexService service;

    @BeforeEach
    void setUp() {
        lenient().when(indexNameProvider.getIndexName(any(UUID.class))).thenReturn("idx");
        lenient().when(indexNameProvider.getIndexName(any(Document.class))).thenReturn("idx");
        service = new OpenSearchIndexService(client, indexNameProvider, metadataService, jsonUtils);
    }

    // ==================== getValueToIndex ====================

    @Test
    void getValueToIndex_nullValue_returnsNull() {
        assertNull(service.getValueToIndex("content", null));
    }

    @Test
    void getValueToIndex_dateValue_formatsAsIsoOffset() {
        OffsetDateTime now = OffsetDateTime.now();
        Object result = service.getValueToIndex("createdAt", now);
        assertEquals(now.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME), result);
    }

    @Test
    void getValueToIndex_metadataValue_convertsToMap() {
        Json json = Json.of("{}");
        when(jsonUtils.toMap(json)).thenReturn(Map.of("a", "b"));
        assertEquals(Map.of("a", "b"), service.getValueToIndex("metadata", json));
    }

    @Test
    void getValueToIndex_defaultKey_returnsValueAsIs() {
        assertEquals("plain", service.getValueToIndex("name", "plain"));
    }

    // ==================== updateMetadata ====================

    @Test
    void updateMetadata_nullMetadata_updatesWithNull() throws Exception {
        doReturn(CompletableFuture.completedFuture(null)).when(client).update(any(UpdateRequest.class), eq(Void.class));
        Document doc = Document.builder().id(UUID.randomUUID()).metadata(null).build();

        StepVerifier.create(service.updateMetadata(doc)).verifyComplete();
    }

    @Test
    void updateMetadata_withMetadata_convertsAndUpdates() throws Exception {
        Json json = Json.of("{\"k\":1}");
        when(jsonUtils.toMap(json)).thenReturn(Map.of("k", 1));
        doReturn(CompletableFuture.completedFuture(null)).when(client).update(any(UpdateRequest.class), eq(Void.class));
        Document doc = Document.builder().id(UUID.randomUUID()).metadata(json).build();

        StepVerifier.create(service.updateMetadata(doc)).verifyComplete();
    }

    // ==================== doUpdateIndexField ====================

    @Test
    void doUpdateIndexField_ioException_mapsToError() throws Exception {
        when(client.update(any(UpdateRequest.class), eq(Void.class))).thenThrow(new IOException("os down"));

        StepVerifier.create(service.doUpdateIndexField(UUID.randomUUID(), "content", "x"))
                .expectError(RuntimeException.class)
                .verify();
    }

    // ==================== deleteDocument ====================

    @Test
    void deleteDocument_success_completes() throws Exception {
        doReturn(CompletableFuture.completedFuture(null)).when(client).delete(any(DeleteRequest.class));

        StepVerifier.create(service.deleteDocument(UUID.randomUUID())).verifyComplete();
    }

    @Test
    void deleteDocument_ioException_errors() throws Exception {
        when(client.delete(any(DeleteRequest.class))).thenThrow(new IOException("os down"));

        StepVerifier.create(service.deleteDocument(UUID.randomUUID()))
                .expectError(IOException.class)
                .verify();
    }

    // ==================== copyDocumentWithNewId ====================

    @Test
    @SuppressWarnings("unchecked")
    void copyDocumentWithNewId_notFound_errors() throws Exception {
        GetResponse<Map> getResponse = mock(GetResponse.class);
        when(getResponse.found()).thenReturn(false);
        doReturn(CompletableFuture.completedFuture(getResponse))
                .when(client).get(any(Function.class), eq(Map.class));

        StepVerifier.create(service.copyDocumentWithNewId("idx", UUID.randomUUID(), UUID.randomUUID()))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    @SuppressWarnings("unchecked")
    void copyDocumentWithNewId_nullSource_errors() throws Exception {
        GetResponse<Map> getResponse = mock(GetResponse.class);
        when(getResponse.found()).thenReturn(true);
        when(getResponse.source()).thenReturn(null);
        doReturn(CompletableFuture.completedFuture(getResponse))
                .when(client).get(any(Function.class), eq(Map.class));

        StepVerifier.create(service.copyDocumentWithNewId("idx", UUID.randomUUID(), UUID.randomUUID()))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    @SuppressWarnings("unchecked")
    void copyDocumentWithNewId_getIoException_errors() throws Exception {
        when(client.get(any(Function.class), eq(Map.class))).thenThrow(new IOException("boom"));

        StepVerifier.create(service.copyDocumentWithNewId("idx", UUID.randomUUID(), UUID.randomUUID()))
                .expectError(RuntimeException.class)
                .verify();
    }

    // ==================== indexMetadata ====================

    @Test
    void indexMetadata_ioException_propagates() throws Exception {
        when(client.index(any(IndexRequest.class))).thenThrow(new IOException("boom"));

        StepVerifier.create(service.indexMetadata(UUID.randomUUID(), Map.of("a", "b")))
                .expectError()
                .verify();
    }

    // ==================== indexDocumentStream ====================

    @Test
    void indexDocumentStream_emptyFragments_completesWithoutIndexing() {
        StepVerifier.create(service.indexDocumentStream(Flux.just("", ""), UUID.randomUUID()))
                .verifyComplete();
        verifyNoInteractions(client);
    }

    @Test
    void indexDocumentStream_withContent_updatesContentField() throws Exception {
        doReturn(CompletableFuture.completedFuture(null)).when(client).update(any(UpdateRequest.class), eq(Void.class));

        StepVerifier.create(service.indexDocumentStream(Flux.just("hello", "world"), UUID.randomUUID()))
                .verifyComplete();

        verify(client).update(any(UpdateRequest.class), eq(Void.class));
    }
}
