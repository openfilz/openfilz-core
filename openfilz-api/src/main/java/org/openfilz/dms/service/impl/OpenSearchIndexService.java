package org.openfilz.dms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.IndexNameProvider;
import org.openfilz.dms.service.IndexService;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Map;

@Service
@RequiredArgsConstructor
@ConditionalOnProperties({
        @ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true"),
        @ConditionalOnProperty(name = "openfilz.full-text.custom-index-service", havingValue = "false", matchIfMissing = true)
})
public class OpenSearchIndexService implements IndexService {

    private final OpenSearchAsyncClient openSearchAsyncClient;
    private final ObjectMapper objectMapper;
    private final IndexNameProvider indexNameProvider;

    @Override
    public Mono<Void> indexDocument(Document document, Mono<String> textMono) {
        if (document.getId() == null) {
            return Mono.error(new IllegalArgumentException("Document ID cannot be null or empty for indexing."));
        }

        return textMono
                .flatMap(text -> {
                    // Créer une map pour le document à indexer, incluant le texte
                    Map<String, Object> source = objectMapper.convertValue(document, Map.class);
                    source.put("content", text); // Ajouter le texte extrait

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
                            .doOnSuccess(response -> System.out.println("Document " + response.id() + " indexed with version " + response.version()))
                            .onErrorResume(e -> Mono.error(new RuntimeException("Failed to index document " + document.getId(), e)))
                            .then(); // Convertir le Mono<IndexResponse> en Mono<Void>
                });
    }

}
