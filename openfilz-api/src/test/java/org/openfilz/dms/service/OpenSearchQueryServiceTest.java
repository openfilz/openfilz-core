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

    // ----- the filters shared with the database path, mapped onto the index fields -----

    private String clauses(FilterInput... filters) {
        BoolQuery.Builder builder = new BoolQuery.Builder();
        service.addFilterClauses(List.of(filters), builder).block();
        return json(builder.build().toQuery());
    }

    @Test
    void type_isTheExistenceOfAnExtension() {
        String files = clauses(new FilterInput("type", "FILE"));
        org.junit.jupiter.api.Assertions.assertTrue(files.contains("\"exists\":{\"field\":\"extension\"}"), files);
        org.junit.jupiter.api.Assertions.assertFalse(files.contains("must_not"), files);
        String folders = clauses(new FilterInput("type", "FOLDER"));
        org.junit.jupiter.api.Assertions.assertTrue(folders.contains("\"must_not\""), folders);
        org.junit.jupiter.api.Assertions.assertFalse(folders.contains("type.keyword"), folders);
    }

    @Test
    void contentType_matchesPatternsAndFallsBackOnExtensions() {
        String query = clauses(new FilterInput("contentType", "image/%, application/pdf"));
        org.junit.jupiter.api.Assertions.assertTrue(query.contains("\"prefix\":{\"contentType\""), query);
        org.junit.jupiter.api.Assertions.assertTrue(query.contains("\"image/\""), query);
        org.junit.jupiter.api.Assertions.assertTrue(query.contains("\"term\":{\"contentType\""), query);
        // entries indexed without contentType: png / pdf by extension
        org.junit.jupiter.api.Assertions.assertTrue(query.contains("\"png\""), query);
        org.junit.jupiter.api.Assertions.assertTrue(query.contains("\"pdf\""), query);
        org.junit.jupiter.api.Assertions.assertTrue(query.contains("\"minimum_should_match\":\"1\""), query);
    }

    @Test
    void owner_dates_size_parent_useTheFieldsThemselves() {
        String query = clauses(
                new FilterInput("createdBy", "alice"),
                new FilterInput("updatedAtAfter", "2026-01-01T00:00:00Z"),
                new FilterInput("createdAtBefore", "2026-02-01T00:00:00+01:00"),
                new FilterInput("size", "42"),
                new FilterInput("parentId", "p1"),
                new FilterInput("extension", ".PDF,txt"));
        org.junit.jupiter.api.Assertions.assertTrue(query.contains("\"createdBy\":{\"value\":\"alice\""), query);
        org.junit.jupiter.api.Assertions.assertFalse(query.contains("createdBy.keyword"), query);
        org.junit.jupiter.api.Assertions.assertTrue(query.contains("\"range\":{\"updatedAt\":{\"gte\""), query);
        org.junit.jupiter.api.Assertions.assertTrue(query.contains("\"range\":{\"createdAt\":{\"lte\""), query);
        org.junit.jupiter.api.Assertions.assertTrue(query.contains("\"size\":{\"value\":42"), query);
        org.junit.jupiter.api.Assertions.assertTrue(query.contains("\"parentId\":{\"value\":\"p1\""), query);
        org.junit.jupiter.api.Assertions.assertTrue(query.contains("[\"pdf\",\"txt\"]"), query);
    }

    @Test
    void unparsableDate_filtersNothing_otherFields_keepTheKeywordClause() {
        String query = clauses(new FilterInput("updatedAtAfter", "yesterday"), new FilterInput("metadata.owner", "Joe"));
        org.junit.jupiter.api.Assertions.assertFalse(query.contains("range"), query);
        org.junit.jupiter.api.Assertions.assertTrue(query.contains("metadata.owner.keyword"), query);
    }

    @Test
    void nullValue_isSkipped() {
        String query = clauses(new FilterInput("createdBy", null));
        org.junit.jupiter.api.Assertions.assertFalse(query.contains("createdBy"), query);
    }

    /** The JSON the client sends for this object. */
    static String json(org.opensearch.client.json.JsonpSerializable value) {
        java.io.StringWriter writer = new java.io.StringWriter();
        org.opensearch.client.json.jackson.JacksonJsonpMapper mapper = new org.opensearch.client.json.jackson.JacksonJsonpMapper();
        try (jakarta.json.stream.JsonGenerator generator = mapper.jsonProvider().createGenerator(writer)) {
            value.serialize(generator, mapper);
        }
        return writer.toString();
    }
}
