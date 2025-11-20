package org.openfilz.dms.service.impl;

import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.DocumentSearchInfo;
import org.openfilz.dms.dto.response.DocumentSearchResult;
import org.openfilz.dms.exception.OpenSearchException;
import org.openfilz.dms.service.DocumentSearchService;
import org.openfilz.dms.service.IndexNameProvider;
import org.openfilz.dms.service.OpenSearchQueryService;
import org.openfilz.dms.service.OpenSearchService;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.*;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true")
public class OpenSearchDocumentSearchService implements DocumentSearchService, OpenSearchService {


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

                    // 7. Execute the request asynchronously
                    SearchRequest searchRequest = requestBuilder
                            .source(fn -> fn.filter(v ->
                                    v.excludes(NAME_SUGGEST, openSearchQueryService.getSourceOtherExclusions()))).build();
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

        String trimQuery = query != null && !query.isEmpty() ? query.trim() : null;

        // 3. Add Full-Text Search Clause (must)
        // This clause contributes to the relevance score.
        if (trimQuery != null) {
            // --- 3. Build the Multi-Match Bool-Prefix Query ---
            MultiMatchQuery multiMatchQuery = MultiMatchQuery.of(m -> m
                    .query(trimQuery)
                    .type(TextQueryType.BoolPrefix)
                    .fields(
                            CONTENT,
                            SUGGEST_OTHERS_2
                    )
            );

            // --- 4. Build the Fuzzy Match Query ---
            MatchQuery fuzzyMatchQuery = MatchQuery.of(m -> m
                    .field(NAME_SUGGEST)
                    .query(FieldValue.of(trimQuery))
                    .fuzziness("AUTO")
                    .operator(Operator.And)
            );
            boolQueryBuilder.should(multiMatchQuery.toQuery(),
                    fuzzyMatchQuery.toQuery()).minimumShouldMatch("1");

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
                .map(Hit::source)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        long totalHits = response.hits().total() != null ? response.hits().total().value() : 0;

        return new DocumentSearchResult(totalHits, documents);
    }
}