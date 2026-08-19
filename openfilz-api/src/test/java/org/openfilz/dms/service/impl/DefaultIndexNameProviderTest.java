package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.config.FullTextProperties;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.IndexMappingsProvider;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexResponse;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesAsyncClient;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultIndexNameProviderTest {

    @Mock
    private OpenSearchAsyncClient client;
    @Mock
    private OpenSearchIndicesAsyncClient indicesClient;
    @Mock
    private FullTextProperties fullTextProperties;

    // All-default interface — use a real instance so getIndexMappings()/getIndexSettings() work.
    private final IndexMappingsProvider indexMappingsProvider = new IndexMappingsProvider() {};

    private DefaultIndexNameProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DefaultIndexNameProvider(client, indexMappingsProvider, fullTextProperties);
        ReflectionTestUtils.setField(provider, "defaultIndexName", "openfilz");
    }

    @Test
    void getIndexName_alwaysReturnsDefault() {
        assertEquals("openfilz", provider.getIndexName(mock(Document.class)));
        assertEquals("openfilz", provider.getIndexName(UUID.randomUUID()));
        assertEquals("openfilz", provider.getDocumentsIndexName());
    }

    @Test
    void indexExists_returnsTrue() throws Exception {
        when(client.indices()).thenReturn(indicesClient);
        when(indicesClient.exists(any(ExistsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new BooleanResponse(true)));

        StepVerifier.create(provider.indexExists("openfilz"))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void indexExists_ioException_isWrapped() throws Exception {
        when(client.indices()).thenReturn(indicesClient);
        when(indicesClient.exists(any(ExistsRequest.class))).thenThrow(new IOException("os down"));

        StepVerifier.create(provider.indexExists("openfilz"))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void createIndex_whenExists_skipsCreation() throws Exception {
        when(client.indices()).thenReturn(indicesClient);
        when(indicesClient.exists(any(ExistsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new BooleanResponse(true)));

        StepVerifier.create(provider.createIndex("openfilz")).verifyComplete();

        verify(indicesClient, never()).create(any(CreateIndexRequest.class));
    }

    @Test
    void createIndex_whenMissing_createsIndex() throws Exception {
        when(client.indices()).thenReturn(indicesClient);
        when(indicesClient.exists(any(ExistsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new BooleanResponse(false)));
        when(fullTextProperties.getContentLanguages()).thenReturn(List.of("en"));
        CreateIndexResponse createResponse = mock(CreateIndexResponse.class);
        when(createResponse.acknowledged()).thenReturn(true);
        when(indicesClient.create(any(CreateIndexRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(createResponse));

        StepVerifier.create(provider.createIndex("openfilz")).verifyComplete();

        verify(indicesClient).create(any(CreateIndexRequest.class));
    }
}
