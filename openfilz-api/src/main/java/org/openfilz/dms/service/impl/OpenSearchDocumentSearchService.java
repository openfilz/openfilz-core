package org.openfilz.dms.service.impl;

import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.DocumentSearchInfo;
import org.openfilz.dms.dto.response.DocumentSearchResult;
import org.openfilz.dms.enums.OpenSearchDocumentKey;
import org.openfilz.dms.exception.OpenSearchException;
import org.openfilz.dms.service.DocumentSearchService;
import org.openfilz.dms.service.IndexNameProvider;
import org.openfilz.dms.service.OpenSearchQueryService;
import org.openfilz.dms.service.OpenSearchService;
import org.openfilz.dms.utils.DocumentSearchUtil;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.*;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Lazy
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true")
public class OpenSearchDocumentSearchService implements DocumentSearchService, OpenSearchService {

    private static final String MARK = "<mark>";
    private static final String MARK_END = "</mark>";
    private static final String ELLIPSIS = "…";
    /** Characters around the match in the content extract of a hit. */
    private static final int SNIPPET_FRAGMENT_SIZE = 160;


    private final IndexNameProvider indexNameProvider;
    private final OpenSearchQueryService openSearchQueryService;
    private final OpenSearchAsyncClient client;

    /**
     * Searches documents based on GraphQL arguments.
     *
     * @return A Mono emitting the search result.
     */
    @Override
    public Mono<DocumentSearchResult> search(String query, List<FilterInput> filters, SortInput sort, int page, int size, DataFetchingEnvironment environment) {
        if(page < 1 ) {
            throw new IllegalArgumentException("page must be equals or greater than 1");
        }
        // 1. Build the main Search Request
        SearchRequest.Builder requestBuilder = new SearchRequest.Builder();
        requestBuilder.index(indexNameProvider.getDocumentsIndexName());

        // 2. Build the Bool Query (the container for all clauses)
        BoolQuery.Builder boolQueryBuilder = getBoolQueryBuilder(query);

        return openSearchQueryService.addFilterClauses(filters, boolQueryBuilder)
                .flatMap(b -> {
                    // Finalize the query
                    Query finalQuery = new Query.Builder().bool(b.build()).build();
                    requestBuilder.query(finalQuery);

                    addSorting(sort, requestBuilder);

                    // 6. Add Pagination
                    requestBuilder.from((page - 1) * size).size(size);

                    // The best matching extract of the content, for the results list
                    if (query != null && !query.isBlank()) {
                        requestBuilder.highlight(h -> h
                                .fields(CONTENT, f -> f
                                        .preTags(MARK)
                                        .postTags(MARK_END)
                                        .fragmentSize(SNIPPET_FRAGMENT_SIZE)
                                        .numberOfFragments(1)
                                        .noMatchSize(0)));
                    }

                    // 7. Execute the request asynchronously
                    SearchRequest searchRequest = requestBuilder
                            // The insight mirror adds category / summary / language to the source: the first two
                            // facets travel with the hit (DocumentSearchInfo), the summary is long text nobody lists
                            .source(fn -> fn.filter(v ->
                                    v.excludes(NAME_SUGGEST, openSearchQueryService.getSourceOtherExclusions()).excludes(ACTIVE)
                                            .excludes(OpenSearchDocumentKey.summary.toString()))).build();
                    try {
                        return Mono.fromFuture(client.search(searchRequest, DocumentSearchInfo.class))
                                .map(this::toDocumentSearchResult); // Convert the response to our DTO
                    } catch (IOException e) {
                        return Mono.error(new OpenSearchException(e));
                    }
                });
    }

    private BoolQuery.Builder getBoolQueryBuilder(String query) {
        BoolQuery.Builder boolQueryBuilder = new BoolQuery.Builder();

        // Filter to only include active (non-deleted) documents
        boolQueryBuilder.filter(f -> f.term(t -> t
                .field(ACTIVE)
                .value(FieldValue.of(true))
        ));

        String trimQuery = query != null && !query.isEmpty() ? query.trim() : null;

        // 3. Add Full-Text Search Clause (must)
        // This clause contributes to the relevance score.
        if (trimQuery != null) {
            // --- 3. Build the Multi-Match Bool-Prefix Query (content + name) ---
            List<Query> should = new ArrayList<>(List.of(
                    MultiMatchQuery.of(m -> m
                            .query(trimQuery)
                            .type(TextQueryType.BoolPrefix)
                            .fields(CONTENT)
                    ).toQuery()));
            // name_suggest is indexed with '_' / '-' / camelCase split into words: the name clauses
            // read the query as typed and, when it differs, split the same way ("abc_123" → "abc 123")
            String nameQuery = DocumentSearchUtil.splitWithSpaces(trimQuery);
            for (String q : nameQuery.equals(trimQuery) ? List.of(trimQuery) : List.of(trimQuery, nameQuery)) {
                should.add(MultiMatchQuery.of(m -> m
                        .query(q)
                        .type(TextQueryType.BoolPrefix)
                        .fields(Arrays.asList(SUGGEST_OTHERS_2))
                ).toQuery());
                // --- 4. Build the Fuzzy Match Query ---
                should.add(MatchQuery.of(m -> m
                        .field(NAME_SUGGEST)
                        .query(FieldValue.of(q))
                        .fuzziness("AUTO")
                        .operator(Operator.And)
                ).toQuery());
            }
            boolQueryBuilder.should(should).minimumShouldMatch("1");
        }
        return boolQueryBuilder;
    }

    /**
     * Converts the raw OpenSearch response into our domain-specific DTO.
     */
    private DocumentSearchResult toDocumentSearchResult(SearchResponse<DocumentSearchInfo> response) {
        if (response.hits() == null) {
            return new DocumentSearchResult(0L, Collections.emptyList());
        }

        List<DocumentSearchInfo> documents = response.hits().hits().stream()
                .map(this::toDocumentSearchInfo)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        long totalHits = response.hits().total() != null ? response.hits().total().value() : 0;

        return new DocumentSearchResult(totalHits, documents);
    }

    /** The hit's source, with the content extract the highlighter found (matches in {@code <mark>}). */
    private DocumentSearchInfo toDocumentSearchInfo(Hit<DocumentSearchInfo> hit) {
        DocumentSearchInfo source = hit.source();
        if (source == null) {
            return null;
        }
        if (hit.highlight() != null) {
            List<String> fragments = hit.highlight().get(CONTENT);
            if (fragments != null && !fragments.isEmpty() && !fragments.getFirst().isBlank()) {
                return source.withContentSnippet(ELLIPSIS + fragments.getFirst().strip() + ELLIPSIS);
            }
        }
        return source;
    }
}
