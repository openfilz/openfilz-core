package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.enums.OpenSearchDocumentKey;
import org.openfilz.dms.service.IndexNameProvider;
import org.openfilz.dms.service.IndexedMetadataKeys;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.GetMappingRequest;
import org.opensearch.client.opensearch.indices.GetMappingResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the key spellings off the documents index mapping: {@code metadata} is a dynamic object,
 * so every key a document was ever indexed with is a field of it (nested objects as dotted
 * paths). One cluster-state read per search carrying metadata filters, whatever the corpus size
 * — unlike a scan of the documents holding the key. A new spelling is visible as soon as its
 * first document is indexed, which is also when that document becomes searchable.
 */
@Service
@Lazy
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true")
public class OpenSearchMetadataKeys implements IndexedMetadataKeys {

    private final OpenSearchAsyncClient client;
    private final IndexNameProvider indexNameProvider;

    @Override
    public Mono<Map<String, List<String>>> spellings(Collection<String> keys) {
        if (keys.isEmpty()) {
            return Mono.just(Map.of());
        }
        return indexedKeys().map(indexed -> {
            Map<String, List<String>> spellings = new LinkedHashMap<>();
            for (String key : keys) {
                // sorted: the same filter always builds the same query (the index caches filters by their text)
                List<String> matches = indexed.stream().filter(k -> k.equalsIgnoreCase(key)).sorted().toList();
                if (!matches.isEmpty()) {
                    spellings.put(key, matches);
                }
            }
            return spellings;
        });
    }

    /** Every metadata key of the documents index mapping, nested objects as dotted paths. */
    Mono<Set<String>> indexedKeys() {
        GetMappingRequest request = GetMappingRequest.of(b -> b.index(indexNameProvider.getDocumentsIndexName()));
        return Mono.fromFuture(() -> {
                    try {
                        return client.indices().getMapping(request);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .map(OpenSearchMetadataKeys::metadataKeys)
                // an unreadable mapping degrades to the keys as typed, never to a failed search
                .onErrorResume(e -> {
                    log.warn("Could not read the metadata keys of the documents index: {}", e.getMessage());
                    return Mono.just(Set.of());
                });
    }

    static Set<String> metadataKeys(GetMappingResponse response) {
        Set<String> keys = new LinkedHashSet<>();
        response.result().values().forEach(record -> {
            TypeMapping mappings = record.mappings();
            Property metadata = mappings == null ? null : mappings.properties().get(OpenSearchDocumentKey.metadata.toString());
            if (metadata != null && metadata.isObject()) {
                collect("", metadata.object().properties(), keys);
            }
        });
        return keys;
    }

    private static void collect(String prefix, Map<String, Property> properties, Set<String> keys) {
        for (Map.Entry<String, Property> entry : properties.entrySet()) {
            String path = prefix + entry.getKey();
            keys.add(path);
            if (entry.getValue().isObject()) {
                collect(path + ".", entry.getValue().object().properties(), keys);
            }
        }
    }
}
