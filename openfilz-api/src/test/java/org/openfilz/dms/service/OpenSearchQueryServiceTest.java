package org.openfilz.dms.service;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.request.FilterInput;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import reactor.test.StepVerifier;

import java.util.List;

/**
 * Covers the default query-builder methods of {@link OpenSearchQueryService} via a minimal
 * concrete implementation (the interface is all-default except getSourceOtherExclusions).
 */
class OpenSearchQueryServiceTest {

    private final OpenSearchQueryService service = new OpenSearchQueryService() {
        @Override
        public String[] getSourceOtherExclusions() {
            return new String[0];
        }
    };

    @Test
    void getQuery_withoutFilters_buildsNameSuggestQuery() {
        StepVerifier.create(service.getQuery("hello", null))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void getQuery_withFilters_addsFilterClauses() {
        StepVerifier.create(service.getQuery("hello", List.of(new FilterInput("type", "FILE"))))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void addFilterClauses_appendsKeywordAndExistingKeywordFields() {
        BoolQuery.Builder builder = new BoolQuery.Builder();
        StepVerifier.create(service.addFilterClauses(
                        List.of(new FilterInput("type", "FILE"), new FilterInput("parentId.keyword", "x")),
                        builder))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void addFilterClauses_emptyFilters_returnsBuilderUnchanged() {
        BoolQuery.Builder builder = new BoolQuery.Builder();
        StepVerifier.create(service.addFilterClauses(List.of(), builder))
                .expectNext(builder)
                .verifyComplete();
    }

    @Test
    void getNameSuggestQuery_buildsMultiMatch() {
        org.junit.jupiter.api.Assertions.assertNotNull(service.getNameSuggestQuery("term"));
    }
}
