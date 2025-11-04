package org.openfilz.dms.service.impl;

import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.OpenSearchDocumentKey;
import org.openfilz.dms.service.IndexNameProvider;
import org.openfilz.dms.service.IndexService;
import org.openfilz.dms.utils.JsonUtils;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch.core.DeleteRequest;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.core.UpdateRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperties({
        @ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true"),
        @ConditionalOnProperty(name = "openfilz.full-text.custom-index-service", havingValue = "false", matchIfMissing = true)
})
public class OpenSearchIndexService implements IndexService {

    private final OpenSearchAsyncClient openSearchAsyncClient;
    private final IndexNameProvider indexNameProvider;
    private final JsonUtils jsonUtils;



    @Override
    public Mono<Void> updateMetadata(Document document) {
        // Create a map for the partial update (doc_as_upsert = true allows to create if not exists)
        Object value = getMetadataObject(document.getMetadata());
        return doUpdateIndexField(document, OpenSearchDocumentKey.metadata.toString(), value);
    }

    private Object getMetadataObject(Json metadata) {
        return metadata != null ? jsonUtils.toMap(metadata) : null;
    }

    @Override
    public Mono<Void> updateIndexField(Document document, String key, Object value) {
        Object valueToIndex = getValueToIndex(key, value);
        return doUpdateIndexField(document, key, valueToIndex);
    }

    @Override
    public Mono<Void> deleteDocument(UUID id) {
        // Build the DeleteRequest
        DeleteRequest deleteRequest = new DeleteRequest.Builder()
                .index(indexNameProvider.getIndexName(id))
                .id(id.toString())
                .build();

        // Execute the delete asynchronously and convert the CompletableFuture to Mono
        try {
            return Mono.fromFuture(openSearchAsyncClient.delete(deleteRequest)).then();
        } catch (IOException e) {
            return Mono.error(e);
        }
    }


    private Object getValueToIndex(String key, Object value) {
        if(value == null) {
            return null;
        }
        return switch (OpenSearchDocumentKey.valueOf(key)) {
            case OpenSearchDocumentKey.metadata -> jsonUtils.toMap((Json) value);
            case OpenSearchDocumentKey.createdAt,
                 OpenSearchDocumentKey.updatedAt -> ((OffsetDateTime)value).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            default -> value;
        };
    }

    private Mono<Void> doUpdateIndexField(Document document, String key, Object value) {
        Map<String, Object> updateDoc = Collections.singletonMap(key, value);
        // Build the UpdateRequest
        UpdateRequest<Void, Map<String, Object>> updateRequest = new UpdateRequest.Builder<Void, Map<String, Object>>()
                .index(indexNameProvider.getIndexName(document))
                .id(document.getId().toString())
                .doc(updateDoc)
                .build();

        // Execute the update asynchronously and convert the CompletableFuture to Mono
        try {
            return Mono.fromFuture(openSearchAsyncClient.update(updateRequest, Void.class))
                    .then();
        } catch (IOException e) {
            return Mono.error(new RuntimeException("Failed to update document " + document.getId(), e));
        }
    }

    @Override
    public Mono<Void> copyIndex(UUID sourceFileId, Document createdDocument) {
        return copyDocumentWithNewId(indexNameProvider.getIndexName(createdDocument), sourceFileId, createdDocument.getId())
                .then();
    }



    @Override
    public Map<String, Object> newOpenSearchDocumentMetadata(Document document) {
        Map<String, Object> source = new HashMap<>(OpenSearchDocumentKey.values().length);
        source.put(OpenSearchDocumentKey.id.toString(), document.getId());
        source.put(OpenSearchDocumentKey.name.toString(), document.getName());
        source.put(OpenSearchDocumentKey.contentType.toString(), document.getContentType());
        source.put(OpenSearchDocumentKey.size.toString(), document.getSize());
        source.put(OpenSearchDocumentKey.parentId.toString(), document.getParentId());
        source.put(OpenSearchDocumentKey.createdAt.toString(), document.getCreatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        source.put(OpenSearchDocumentKey.createdBy.toString(), document.getCreatedBy());
        source.put(OpenSearchDocumentKey.updatedAt.toString(), document.getUpdatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        source.put(OpenSearchDocumentKey.updatedBy.toString(), document.getUpdatedBy());
        Json metadata = document.getMetadata();
        if(metadata != null) {
            source.put(OpenSearchDocumentKey.metadata.toString(), jsonUtils.toMap(metadata));
        }
        return source;
    }

    /**
     * Copies an existing document to a new document within the same index,
     * maintaining all original metadata (source fields), and assigns a specific new ID.
     *
     * @param indexName The name of the index.
     * @param originalDocumentId The ID of the document to copy.
     * @param newDocumentId The desired ID for the new document.
     * @return A Mono emitting the IndexResponse for the new document, or an error.
     */
    public Mono<IndexResponse> copyDocumentWithNewId(String indexName, UUID originalDocumentId, UUID newDocumentId) {
        try {
            return Mono.fromFuture(openSearchAsyncClient.get(g -> g
                            .index(indexName)
                            .id(originalDocumentId.toString()), Map.class)) // We expect the source as a Map
                    .flatMap(getResponse -> {
                        if (!getResponse.found()) {
                            return Mono.error(new RuntimeException("Original document with ID " + originalDocumentId + " not found."));
                        }

                        Map<String, Object> originalSource = getResponse.source();
                        if (originalSource == null) {
                            return Mono.error(new RuntimeException("Original document source is null for ID " + originalDocumentId));
                        }

                        // 2. Prepare the new document source (no changes needed for simple copy)
                        // You could modify fields here if needed.

                        // 3. Index the new document with the specified ID
                        IndexRequest<Map<String, Object>> indexRequest = new IndexRequest.Builder<Map<String, Object>>()
                                .index(indexName)
                                .id(newDocumentId.toString()) // Specify the new ID here
                                .document(originalSource)
                                .build();

                        try {
                            return Mono.fromFuture(openSearchAsyncClient.index(indexRequest));
                        } catch (IOException e) {
                            return Mono.error(new RuntimeException("Failed to index document with ID " + newDocumentId, e));
                        }
                    });
        } catch (IOException e) {
            return Mono.error(new RuntimeException("Failed to index document with ID " + newDocumentId, e));
        }

    }


}
