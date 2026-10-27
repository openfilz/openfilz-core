package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.service.IndexNameProvider;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.GetMappingRequest;
import org.opensearch.client.opensearch.indices.GetMappingResponse;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesAsyncClient;
import org.opensearch.client.opensearch.indices.get_mapping.IndexMappingRecord;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenSearchMetadataKeysTest {

    private static final Property TEXT = Property.of(p -> p.text(t -> t.fields("keyword", f -> f.keyword(k -> k))));
    private static final Property LONG = Property.of(p -> p.long_(l -> l));

    /** What a dynamic {@code metadata} object looks like once documents carried these keys. */
    private static GetMappingResponse mapping() {
        Property metadata = Property.of(p -> p.object(o -> o
                .properties("client", TEXT)
                .properties("Client", TEXT)
                .properties("n", LONG)
                .properties("invoice", Property.of(i -> i.object(io -> io.properties("Number", TEXT))))));
        TypeMapping mappings = TypeMapping.of(t -> t
                .properties("name", TEXT)
                .properties("metadata", metadata));
        return GetMappingResponse.of(r -> r.result(Map.of("openfilz", IndexMappingRecord.of(m -> m.mappings(mappings)))));
    }

    @Test
    void metadataKeys_listsEverySpellingNestedObjectsAsDottedPaths() {
        assertThat(OpenSearchMetadataKeys.metadataKeys(mapping()))
                .containsExactlyInAnyOrder("client", "Client", "n", "invoice", "invoice.Number");
    }

    @Test
    void metadataKeys_noMetadataYet_isEmpty() {
        GetMappingResponse empty = GetMappingResponse.of(r -> r.result(Map.of("openfilz",
                IndexMappingRecord.of(m -> m.mappings(TypeMapping.of(t -> t.properties("name", TEXT)))))));
        assertThat(OpenSearchMetadataKeys.metadataKeys(empty)).isEmpty();
    }

    @Test
    void spellings_matchTheKeysIgnoringCase_unknownKeysAbsent() throws Exception {
        OpenSearchMetadataKeys keys = keys(CompletableFuture.completedFuture(mapping()));

        Map<String, List<String>> spellings = keys.spellings(List.of("CLIENT", "invoice.number", "missing")).block();

        assertThat(spellings).containsExactly(
                Map.entry("CLIENT", List.of("Client", "client")),
                Map.entry("invoice.number", List.of("invoice.Number")));
    }

    @Test
    void spellings_unreadableMapping_degradesToNoSpellings() throws Exception {
        OpenSearchMetadataKeys keys = keys(CompletableFuture.failedFuture(new RuntimeException("index closed")));

        assertThat(keys.spellings(Set.of("client")).block()).isEmpty();
    }

    @Test
    void spellings_noKeys_asksNothing() {
        OpenSearchMetadataKeys keys = new OpenSearchMetadataKeys(mock(OpenSearchAsyncClient.class), mock(IndexNameProvider.class));

        assertThat(keys.spellings(List.of()).block()).isEmpty();
    }

    private static OpenSearchMetadataKeys keys(CompletableFuture<GetMappingResponse> answer) throws Exception {
        OpenSearchAsyncClient client = mock(OpenSearchAsyncClient.class);
        OpenSearchIndicesAsyncClient indices = mock(OpenSearchIndicesAsyncClient.class);
        when(client.indices()).thenReturn(indices);
        when(indices.getMapping(any(GetMappingRequest.class))).thenReturn(answer);
        IndexNameProvider indexNameProvider = mock(IndexNameProvider.class);
        when(indexNameProvider.getDocumentsIndexName()).thenReturn("openfilz");
        return new OpenSearchMetadataKeys(client, indexNameProvider);
    }
}
