package org.openfilz.dms.service;

import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.utils.DocumentSearchUtil;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.MultiMatchQuery;
import org.opensearch.client.opensearch._types.query_dsl.QueryVariant;
import org.opensearch.client.opensearch._types.query_dsl.TextQueryType;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.openfilz.dms.service.OpenSearchService.*;

public interface OpenSearchQueryService {

    default Mono<BoolQuery.Builder> addFilterClauses(List<FilterInput> filters, BoolQuery.Builder boolQueryBuilder) {
        // 4. Add Filter Clauses (filter)
        // These clauses are used for exact matching and do not affect the score.
        // They are generally faster and cacheable.
        if (!CollectionUtils.isEmpty(filters)) {
            for (FilterInput filter : filters) {
                if (isInsightFacet(filter.field())) {
                    addInsightFacetClause(filter, boolQueryBuilder);
                    continue;
                }
                // IMPORTANT: For exact matching on text fields, you must use the '.keyword' sub-field.
                // This assumes your OpenSearch mapping for text fields includes a keyword multi-field.
                // For fields like 'parentId' that might already be of type 'keyword', this is still safe.
                String fieldName = filter.field().endsWith(KEYWORD) ? filter.field() : filter.field() + KEYWORD;

                boolQueryBuilder.filter(f -> f.term(t -> t
                        .field(fieldName)
                        .value(FieldValue.of(filter.value()))
                ));
            }
        }
        return Mono.just(boolQueryBuilder);
    }

    /** The insight facets ({@code category}, {@code language}) are keyword fields of their own, mirrored from {@code ai_document_insights}. */
    static boolean isInsightFacet(String field) {
        return DocumentSearchUtil.FILTER_CATEGORY.equals(field) || DocumentSearchUtil.FILTER_LANGUAGE.equals(field);
    }

    /**
     * One {@code terms} filter on the facet's keyword field: the value names one key or several,
     * comma-separated ({@code invoice,quote}); an empty value filters nothing.
     */
    static void addInsightFacetClause(FilterInput filter, BoolQuery.Builder boolQueryBuilder) {
        List<String> keys = DocumentSearchUtil.toKeys(filter.value());
        if (keys == null) {
            return;
        }
        List<FieldValue> values = keys.stream().map(FieldValue::of).toList();
        boolQueryBuilder.filter(f -> f.terms(t -> t
                .field(filter.field())
                .terms(v -> v.value(values))
        ));
    }

    default Mono<? extends QueryVariant> getQuery(String trimQuery, List<FilterInput> filters) {

        if(CollectionUtils.isEmpty(filters)) {
            return getQueryWithoutFilters(trimQuery);
        }

        BoolQuery.Builder boolQueryBuilder = new BoolQuery.Builder();

        boolQueryBuilder.must(getNameSuggestQuery(trimQuery).toQuery());
        return addFilterClauses(filters, boolQueryBuilder)
                .flatMap(b -> Mono.just(b.build()));
    }

    default Mono<? extends QueryVariant> getQueryWithoutFilters(String trimQuery) {
        return Mono.just(getNameSuggestQuery(trimQuery));
    }

    default MultiMatchQuery getNameSuggestQuery(String trimQuery) {
        return MultiMatchQuery.builder().type(TextQueryType.BoolPrefix)
                .query(trimQuery)
                // We target the main field and its internal sub-fields for best results
                .fields(NAME_SUGGEST, SUGGEST_OTHERS)
                .build();
    }

    String[] getSourceOtherExclusions();
}
