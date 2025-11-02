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
import org.opensearch.client.opensearch.core.IndexRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

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
    public Mono<Void> indexDocument(Document document, Mono<String> textMono) {
        if (document.getId() == null) {
            return Mono.error(new IllegalArgumentException("Document ID cannot be null or empty for indexing."));
        }

        return textMono
                .flatMap(text -> {
                    // Créer une map pour le document à indexer, incluant le texte
                    Map<String, Object> source = newOpenSearchDocument(document, text);

                    IndexRequest<Map<String, Object>> request = IndexRequest.of(i -> i
                            .index(indexNameProvider.getIndexName(document))
                            .id(document.getId().toString())
                            .document(source)
                    );

                    // Convertir le CompletableFuture en Mono
                    return Mono.fromFuture(() -> {
                                try {
                                    return openSearchAsyncClient.index(request);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            })
                            .doOnSuccess(response -> log.debug("Document {} indexed with version {}", response.id(), response.version()))
                            .onErrorResume(e -> Mono.error(new RuntimeException("Failed to index document " + document.getId(), e)))
                            .then(); // Convertir le Mono<IndexResponse> en Mono<Void>
                });
    }

    private Map<String, Object> newOpenSearchDocument(Document document, String text) {
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
        source.put(OpenSearchDocumentKey.content.toString(), text);
        Json metadata = document.getMetadata();
        if(metadata != null) {
            source.put(OpenSearchDocumentKey.metadata.toString(), jsonUtils.toMap(metadata));
        }
        return source;
    }


}
