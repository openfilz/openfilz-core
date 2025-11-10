package org.openfilz.dms.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.OpenSearchDocumentKey;
import org.openfilz.dms.service.IndexNameProvider;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch._types.mapping.DynamicMapping;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperties({
        @ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true"),
        @ConditionalOnProperty(name = "openfilz.full-text.custom-index-name", havingValue = "false", matchIfMissing = true)
})
public class DefaultIndexNameProvider implements IndexNameProvider {

    private final OpenSearchAsyncClient openSearchAsyncClient;

    @PostConstruct
    public void init() {
        createIndex(DEFAULT_INDEX_NAME).subscribe();
    }

    @Override
    public String getIndexName(Document document) {
        return DEFAULT_INDEX_NAME;
    }

    @Override
    public String getIndexName(UUID documentId) {
        return DEFAULT_INDEX_NAME;
    }

    @Override
    public String getDocumentsIndexName() {
        return DEFAULT_INDEX_NAME;
    }

    /**
     * Vérifie si un index existe dans OpenSearch.
     * @param indexName Le nom de l'index.
     * @return Mono<Boolean> - true si l'index existe, false sinon.
     */
    public Mono<Boolean> indexExists(String indexName) {
        ExistsRequest request = ExistsRequest.of(e -> e.index(indexName));

        return Mono.fromFuture(() -> {
                    try {
                        return openSearchAsyncClient.indices().exists(request);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .map(BooleanResponse::value)
                .onErrorResume(e -> Mono.error(new RuntimeException("Error checking index existence for " + indexName, e)));
    }

    /**
     * Crée un index dans OpenSearch avec une configuration de base,
     * mais seulement s'il n'existe pas déjà.
     * @param indexName Le nom de l'index à créer.
     * @return Un Mono<Void> qui indique la complétion ou une erreur.
     */
    public Mono<Void> createIndex(String indexName) {
        return indexExists(indexName)
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        log.debug("Index '{}' already exists. Skipping creation.", indexName);
                        return Mono.empty();
                    } else {
                        log.info("Index '{}' does not exist. Creating it...", indexName);

                        CreateIndexRequest createRequest = CreateIndexRequest.of(c -> c
                                        .index(indexName)
                                        .mappings(m -> m
                                                .properties(OpenSearchDocumentKey.id.toString(), p -> p.keyword(k -> k))
                                                .properties(OpenSearchDocumentKey.name.toString(), p -> p.text(tx -> tx.fields("keyword", b-> b.keyword(builder -> builder))))
                                                .properties(OpenSearchDocumentKey.name_suggest.toString(), p -> p.searchAsYouType(builder ->  builder))
                                                .properties(OpenSearchDocumentKey.contentType.toString(), p -> p.keyword(k -> k))
                                                .properties(OpenSearchDocumentKey.size.toString(), p -> p.long_(k -> k))
                                                .properties(OpenSearchDocumentKey.parentId.toString(), p -> p.keyword(k -> k))
                                                .properties(OpenSearchDocumentKey.createdAt.toString(), p -> p.date(k -> k))
                                                .properties(OpenSearchDocumentKey.updatedAt.toString(), p -> p.date(k -> k))
                                                .properties(OpenSearchDocumentKey.createdBy.toString(), p -> p.keyword(k -> k))
                                                .properties(OpenSearchDocumentKey.updatedBy.toString(), p -> p.keyword(k -> k))
                                                .properties(OpenSearchDocumentKey.content.toString(), p -> p.text(tx -> tx))
                                                .properties(OpenSearchDocumentKey.metadata.toString(), p -> p.object(tx -> tx.dynamic(DynamicMapping.True)))
                                        )
                                // Vous pouvez ajouter d'autres settings ici, par exemple :
                                // .settings(s -> s
                                //    .numberOfShards("1")
                                //    .numberOfReplicas("1")
                                // )
                        );

                        return Mono.fromFuture(() -> {
                                    try {
                                        return openSearchAsyncClient.indices().create(createRequest);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                })
                                .doOnSuccess(response -> log.debug("Index '{}' created successfully: {}", indexName, response.acknowledged()))
                                .onErrorResume(e -> Mono.error(new RuntimeException("Failed to create index " + indexName, e)))
                                .then();
                    }
                });
    }
}
