package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.Suggest;
import org.openfilz.dms.exception.OpenSearchException;
import org.openfilz.dms.utils.FileUtils;
import org.openfilz.dms.service.DocumentSuggestionService;
import org.openfilz.dms.service.IndexNameProvider;
import org.openfilz.dms.service.OpenSearchQueryService;
import org.openfilz.dms.service.OpenSearchService;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.MatchQuery;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Lazy
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

        // Build a BoolQuery that searches both name and content fields
        BoolQuery.Builder boolQueryBuilder = new BoolQuery.Builder();

        // Filter: only active documents
        boolQueryBuilder.filter(f -> f.term(t -> t
                .field(ACTIVE)
                .value(FieldValue.of(true))
        ));

        // Should: match on name_suggest (existing behavior)
        boolQueryBuilder.should(openSearchQueryService.getNameSuggestQuery(trimQuery).toQuery());

        // Should: match on content (full-text search inside documents)
        // The content_analyzer (asciifolding + language stemmers) handles accent folding
        // and singular/plural at index time, so no fuzziness is needed here.
        boolQueryBuilder.should(MatchQuery.of(m -> m
                .field(CONTENT)
                .query(FieldValue.of(trimQuery))
        ).toQuery());

        // At least one should clause must match
        boolQueryBuilder.minimumShouldMatch("1");

        // Add filter clauses from user-provided filters
        return openSearchQueryService.addFilterClauses(filters, boolQueryBuilder)
                .flatMapMany(b -> {
                    requestBuilder
                            .index(indexNameProvider.getDocumentsIndexName())
                            .query(b.build().toQuery());
                    addSorting(sort, requestBuilder);
                    SearchRequest searchRequest = requestBuilder
                            .source(s -> s.filter(f -> f.includes(SUGGEST_ID, SUGGEST_EXT, NAME)))
                            .size(SUGGEST_RESULTS_MAX_SIZE)
                            .highlight(h -> h
                                    .fields(NAME_SUGGEST, f -> f.preTags(EM).postTags(EM1))
                                    .fields(CONTENT, f -> f
                                            .preTags("<mark>").postTags("</mark>")
                                            .fragmentSize(120)
                                            .numberOfFragments(1)
                                    )
                            )
                            .build();

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
        if (source == null || source.id() == null) {
            return null;
        }

        // Extract content snippet from highlights (if the match was on content)
        String contentSnippet = null;
        if (hit.highlight() != null) {
            List<String> contentHighlights = hit.highlight().get(CONTENT);
            if (contentHighlights != null && !contentHighlights.isEmpty()) {
                contentSnippet = "..." + contentHighlights.getFirst() + "...";
            }
        }

        String name = FileUtils.removeFileExtension(source.name());
        return new Suggest(source.id(), name, source.extension(), contentSnippet);
    }

}
