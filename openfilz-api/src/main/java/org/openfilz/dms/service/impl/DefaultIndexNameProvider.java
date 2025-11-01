package org.openfilz.dms.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.IndexNameProvider;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperties({
        @ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true"),
        @ConditionalOnProperty(name = "openfilz.full-text.custom-index-name", havingValue = "false", matchIfMissing = true)
})
public class DefaultIndexNameProvider implements IndexNameProvider {

    private static final String OPENFILZ = "openfilz";

    private final OpenSearchAsyncClient openSearchAsyncClient;

    @PostConstruct
    public void init() {
        createIndex(OPENFILZ).subscribe();
    }

    @Override
    public String getIndexName(Document document) {
        return OPENFILZ;
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
                        System.out.println("Index '" + indexName + "' already exists. Skipping creation.");
                        return Mono.empty();
                    } else {
                        System.out.println("Index '" + indexName + "' does not exist. Creating it...");

                        // Définir le mapping des champs
                        Map<String, TypeMapping> mappings = new HashMap<>();
                        mappings.put("properties", TypeMapping.of(t -> t
                                        .properties("id", p -> p.keyword(k -> k)) // ID comme keyword
                                        .properties("title", p -> p.text(tx -> tx)) // Titre comme text
                                        .properties("author", p -> p.keyword(k -> k)) // Auteur comme keyword
                                        .properties("createdAt", p -> p.date(d -> d)) // Date comme date
                                        .properties("content", p -> p.text(tx -> tx)) // Contenu comme text
                                // Ajoutez d'autres champs si votre Document en a
                        ));

                        CreateIndexRequest createRequest = CreateIndexRequest.of(c -> c
                                        .index(indexName)
                                        .mappings(m -> m
                                                .properties("id", p -> p.keyword(k -> k)) // ID comme keyword
                                                .properties("title", p -> p.text(tx -> tx)) // Titre comme text
                                                .properties("author", p -> p.keyword(k -> k)) // Auteur comme keyword
                                                .properties("createdAt", p -> p.date(d -> d)) // Date comme date
                                                .properties("content", p -> p.text(tx -> tx)) // Contenu comme text
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
                                .doOnSuccess(response -> System.out.println("Index '" + indexName + "' created successfully: " + response.acknowledged()))
                                .onErrorResume(e -> Mono.error(new RuntimeException("Failed to create index " + indexName, e)))
                                .then();
                    }
                });
    }
}
