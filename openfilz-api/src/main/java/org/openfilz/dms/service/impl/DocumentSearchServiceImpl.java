package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.DocumentSearchInfo;
import org.openfilz.dms.dto.response.DocumentSearchResult;
import org.openfilz.dms.enums.SortOrder;
import org.openfilz.dms.exception.OpenSearchException;
import org.openfilz.dms.service.DocumentSearchService;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true")
public class DocumentSearchServiceImpl implements DocumentSearchService {

    private static final String DOCUMENTS_INDEX = "documents"; // Your OpenSearch index name

    private final OpenSearchAsyncClient client;

    /**
     * Searches documents based on GraphQL arguments.
     *
     * @return A Mono emitting the search result.
     */
    @Override
    public Mono<DocumentSearchResult> search(String query, List<FilterInput> filters, SortInput sort, int page, int size) {

        // 1. Build the main Search Request
        SearchRequest.Builder requestBuilder = new SearchRequest.Builder();
        requestBuilder.index(DOCUMENTS_INDEX);

        // 2. Build the Bool Query (the container for all clauses)
        BoolQuery.Builder boolQueryBuilder = new BoolQuery.Builder();

        // 3. Add Full-Text Search Clause (must)
        // This clause contributes to the relevance score.
        if (StringUtils.hasText(query)) {
            boolQueryBuilder.must(m -> m.multiMatch(mm -> mm
                    .query(query)
                    .fields("content", "name") // Search in both content and name
            ));
        }

        // 4. Add Filter Clauses (filter)
        // These clauses are used for exact matching and do not affect the score.
        // They are generally faster and cacheable.
        if (!CollectionUtils.isEmpty(filters)) {
            for (FilterInput filter : filters) {
                // IMPORTANT: For exact matching on text fields, you must use the '.keyword' sub-field.
                // This assumes your OpenSearch mapping for text fields includes a keyword multi-field.
                // For fields like 'parentId' that might already be of type 'keyword', this is still safe.
                String fieldName = filter.field().endsWith(".keyword") ? filter.field() : filter.field() + ".keyword";

                boolQueryBuilder.filter(f -> f.term(t -> t
                        .field(fieldName)
                        .value(FieldValue.of(filter.value()))
                ));
            }
        }

        // Finalize the query
        Query finalQuery = new Query.Builder().bool(boolQueryBuilder.build()).build();
        requestBuilder.query(finalQuery);

        // 5. Add Sorting
        if (sort != null && StringUtils.hasText(sort.field())) {
            // Map our enum to the OpenSearch enum
            var osSortOrder = sort.order() == SortOrder.ASC ?
                    org.opensearch.client.opensearch._types.SortOrder.Asc :
                    org.opensearch.client.opensearch._types.SortOrder.Desc;

            // Again, use .keyword for sorting on text fields to sort alphabetically, not by relevance.
            String sortField = sort.field();
            if ("name".equals(sortField) || "contentType".equals(sortField) || "createdBy".equals(sortField)) {
                sortField += ".keyword";
            }

            String finalSortField = sortField;
            requestBuilder.sort(s -> s.field(f -> f.field(finalSortField).order(osSortOrder)));
        }

        // 6. Add Pagination
        requestBuilder.from(page * size).size(size);

        // 7. Execute the request asynchronously
        SearchRequest searchRequest = requestBuilder.build();
        try {
            return Mono.fromFuture(client.search(searchRequest, DocumentSearchInfo.class))
                    .map(this::toDocumentSearchResult); // Convert the response to our DTO
        } catch (IOException e) {
            throw new OpenSearchException(e);
        }
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