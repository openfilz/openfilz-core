package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.Suggest;
import org.openfilz.dms.exception.OpenSearchException;
import org.openfilz.dms.service.DocumentSuggestionService;
import org.openfilz.dms.service.IndexNameProvider;
import org.openfilz.dms.service.OpenSearchQueryService;
import org.openfilz.dms.service.OpenSearchService;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true")
public class OpenSearchDocumentSuggestionService implements DocumentSuggestionService, OpenSearchService {



    private final IndexNameProvider indexNameProvider;
    private final OpenSearchQueryService openSearchQueryService;
    private final OpenSearchAsyncClient client;

    // A private, internal record used only for deserializing the ID from OpenSearch's _source.
    // This is a clean way to avoid creating a public DTO for this specific internal purpose.
    private record DocumentSource(UUID id, String extension, String name) {}

    /**
     * Provides search-as-you-type suggestions, returning a stream of Suggest objects.
     *
     * @param query The user's partial input string.
     * @return A Flux that emits Suggest objects, each containing a document ID and the suggestion text.
     */
    public Flux<Suggest> getSuggestions(String query, List<FilterInput> filters, SortInput sort) {
        if (query == null || query.isBlank()) {
            return Flux.empty();
        }

        String trimQuery = getTrimQuery(query);
        SearchRequest.Builder requestBuilder = new SearchRequest.Builder();
        return openSearchQueryService.getQuery(trimQuery, filters)
                    .flatMapMany(openQuery -> {
                        requestBuilder
                                .index(indexNameProvider.getDocumentsIndexName())
                                .query(openQuery.toQuery());
                        addSorting(sort, requestBuilder);
                        SearchRequest searchRequest = requestBuilder
                                // We don't need the full document source, making the request very lightweight.
                                .source(s -> s.filter(f -> f.includes(SUGGEST_ID, SUGGEST_EXT, NAME)))
                                // We only need a few suggestions for the UI.
                                .size(SUGGEST_RESULTS_MAX_SIZE)
                                // Use highlighting to get the matched parts of the name.
                                .highlight(h -> h
                                        .fields(NAME_SUGGEST, f -> f.preTags(EM).postTags(EM1))
                                )
                                .build();

                        // We use flatMapMany to transform the single response (Mono<SearchResponse>)
                        // into a stream of multiple items (Flux<Suggest>).
                        try {
                            return Mono.fromFuture(client.search(searchRequest, DocumentSource.class))
                                    .flatMapMany(this::toSuggestFlux);
                        } catch (IOException e) {
                            throw new OpenSearchException(e);
                        }
                    });
    }


    /**
     * Converts the OpenSearch response into a Flux of Suggest objects.
     */
    private Flux<Suggest> toSuggestFlux(SearchResponse<DocumentSource> response) {
        if (response.hits() == null || response.hits().hits() == null) {
            return Flux.empty();
        }

        // Convert the list of hits into a stream (Flux).
        return Flux.fromIterable(response.hits().hits())
                // For each hit, attempt to create a Suggest object.
                .mapNotNull(this::createSuggestFromHit)
                // Filter out any hits that couldn't be converted (e.g., missing source or highlight).
                .filter(Objects::nonNull);
    }

    /**
     * Creates a single Suggest object from an OpenSearch Hit, combining the ID from the source
     * and the text from the highlight.
     */
    private Suggest createSuggestFromHit(Hit<DocumentSource> hit) {
        DocumentSource source = hit.source();
        List<String> highlights = hit.highlight().get(NAME_SUGGEST);

        // Ensure we have both the source with an ID and at least one highlight fragment.
        if (source != null && source.id() != null) {
            if(highlights != null && !highlights.isEmpty()) {
                // Take the first highlight fragment and clean the emphasis tags.
                String suggestionText = highlights.getFirst().replaceAll(EM_EM, "");
                return new Suggest(source.id(), suggestionText, source.extension());
            }
            return new Suggest(source.id(), source.name(), source.extension());
        }

        // Return null if we can't construct a valid Suggest object.
        return null;
    }

/*
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

 */
}