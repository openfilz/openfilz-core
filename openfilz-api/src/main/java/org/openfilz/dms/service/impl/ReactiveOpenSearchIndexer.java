package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.service.IndexNameProvider;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.UpdateRequest;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReactiveOpenSearchIndexer {

    private final OpenSearchAsyncClient client;
    private final IndexNameProvider indexNameProvider;

    /**
     * Create metadata entry before streaming text.
     */
    public Mono<Void> indexMetadata(UUID documentId, Map<String, Object> metadata) {
        Map<String, Object> body = new HashMap<>(metadata);
        body.putIfAbsent("doc-status", "indexing");
        body.putIfAbsent("doc-createdAt", Instant.now().toString());

        IndexRequest<Map<String, Object>> request = new IndexRequest.Builder<Map<String, Object>>()
                .index(indexNameProvider.getIndexName(documentId))
                .id(documentId.toString())
                .document(body)
                .build();

        return Mono.fromFuture(() -> {
                    try {
                        return client.index(request);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
               .doOnSuccess(resp -> log.info("Metadata indexed for document {}", documentId))
               .then();
    }

    /**
     * Stream and bulk index text fragments as partial updates.
     */
    public Mono<Void> indexDocumentStream(Flux<String> textFragments, UUID documentId) {
        log.debug("Indexing document stream for document {}", documentId);
        return textFragments
                .bufferTimeout(50, java.time.Duration.ofSeconds(2)) // combine small fragments
                .flatMapSequential(batch -> sendBulkUpdate(documentId, batch))
                .then();
    }

    private Mono<Void> sendBulkUpdate(UUID documentId, List<String> batch) {
        if (batch.isEmpty()) return Mono.empty();

        String concatenated = String.join("\n", batch);
        BulkOperation op = BulkOperation.of(b -> b
                .update(u -> u
                        .index(indexNameProvider.getIndexName(documentId))
                        .id(documentId.toString())
                        .document(Map.of("content", concatenated))
                        .docAsUpsert(true)
                )
        );

        BulkRequest request = new BulkRequest.Builder()
                .operations(Collections.singletonList(op))
                .refresh(Refresh.False)
                .build();

        return Mono.fromFuture(() -> {
                    try {
                        return client.bulk(request);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
               .doOnNext(resp -> {
                   if (resp.errors()) {
                       resp.items().forEach(item -> {
                           if (item.error() != null) {
                               log.error("Bulk error on doc {}: {}", documentId, item.error().reason());
                           }
                       });
                   }
               })
               .onErrorResume(e -> {
                   log.error("Bulk update failed for {}", documentId, e);
                   return Mono.empty();
               })
               .then();
    }

    /**
     * Finalize or update metadata (e.g. mark document as 'ready').
     */
    public Mono<Void> updateMetadata(UUID documentId, Map<String, Object> updates) {
        Map<String, Object> doc = new HashMap<>(updates);
        doc.putIfAbsent("indexedAt", Instant.now().toString());

        UpdateRequest<Void, Map<String, Object>> request =
                new UpdateRequest.Builder<Void, Map<String, Object>>()
                        .index(indexNameProvider.getIndexName(documentId))
                        .id(documentId.toString())
                        .doc(doc)
                        .docAsUpsert(true)
                        .build();

        return Mono.fromFuture(() -> {
                    try {
                        return client.update(request, Void.class);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
               .doOnSuccess(r -> log.info("Metadata updated for document {}", documentId))
               .then();
    }
}
