package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.enums.OpenSearchDocumentKey;
import org.openfilz.dms.exception.OpenSearchException;
import org.openfilz.dms.service.DocumentSuggestionService;
import org.openfilz.dms.service.IndexNameProvider;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch._types.query_dsl.TextQueryType;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true")
public class DocumentSuggestionServiceImpl implements DocumentSuggestionService {

    static final String SUGGEST_MAIN = OpenSearchDocumentKey.name_suggest.toString();
    static final String[] SUGGEST_OTHERS = { "name_suggest._2gram", "name_suggest._3gram" };
    static final int SUGGEST_RESULTS_MAX_SIZE = 10;
    public static final String EM = "<em>";
    public static final String EM1 = "</em>";
    public static final String EM_EM = "<em>|</em>";

    private final IndexNameProvider indexNameProvider;
    private final OpenSearchAsyncClient client;

    @Override
    public Mono<List<String>> getSuggestions(String query) {
        if (query == null || query.isBlank()) {
            return Mono.just(Collections.emptyList());
        }

        SearchRequest searchRequest = new SearchRequest.Builder()
            .index(indexNameProvider.getDocumentsIndexName())
            .query(q -> q
                .multiMatch(mm -> mm
                    // The 'bool_prefix' type is optimized for search-as-you-type fields
                    .type(TextQueryType.BoolPrefix)
                    .query(query)
                    // We target the main field and its internal sub-fields for best results
                    .fields(SUGGEST_MAIN, SUGGEST_OTHERS)
                )
            )
            // We don't need the full document source, making the request very lightweight.
            .source(s -> s.fetch(false))
            // We only need a few suggestions for the UI.
            .size(SUGGEST_RESULTS_MAX_SIZE)
            // Use highlighting to get the matched parts of the name.
            .highlight(h -> h
                .fields(SUGGEST_MAIN, f -> f.preTags(EM).postTags(EM1))
            )
            .build();

        try {
            return Mono.fromFuture(client.search(searchRequest, Void.class))
                       .map(this::extractSuggestionsFromHighlights);
        } catch (IOException e) {
            throw new OpenSearchException(e);
        }
    }

    private List<String> extractSuggestionsFromHighlights(SearchResponse<Void> response) {
        if (response.hits() == null) {
            return Collections.emptyList();
        }

        // Using highlights gives us the exact text that matched.
        return response.hits().hits().stream()
                .map(hit -> hit.highlight().get(SUGGEST_MAIN))
                .filter(highlight -> highlight != null && !highlight.isEmpty())
                // A single hit might have multiple highlight fragments
                .flatMap(Collection::stream)
                // Clean the highlighting tags for the final response
                .map(suggestion -> suggestion.replaceAll(EM_EM, ""))
                .distinct() // Ensure unique suggestions
                .collect(Collectors.toList());
    }
}