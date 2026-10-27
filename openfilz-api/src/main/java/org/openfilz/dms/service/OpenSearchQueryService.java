package org.openfilz.dms.service;

import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.OpenSearchDocumentKey;
import org.openfilz.dms.utils.ContentTypeMapper;
import org.openfilz.dms.utils.DocumentSearchUtil;
import org.openfilz.dms.utils.MetadataCriteria;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.MultiMatchQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.QueryVariant;
import org.opensearch.client.opensearch._types.query_dsl.TextQueryType;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.openfilz.dms.service.OpenSearchService.*;

public interface OpenSearchQueryService {

    default Mono<BoolQuery.Builder> addFilterClauses(List<FilterInput> filters, BoolQuery.Builder boolQueryBuilder) {
        // 4. Add Filter Clauses (filter)
        // These clauses are used for exact matching and do not affect the score.
        // They are generally faster and cacheable.
        List<FilterInput> metadataFilters = new ArrayList<>();
        if (!CollectionUtils.isEmpty(filters)) {
            for (FilterInput filter : filters) {
                if (filter.value() == null) {
                    continue;
                }
                if (isInsightFacet(filter.field())) {
                    addInsightFacetClause(filter, boolQueryBuilder);
                    continue;
                }
                if (addKnownFilterClause(filter, boolQueryBuilder)) {
                    continue;
                }
                if (filter.field().startsWith(DocumentSearchUtil.FILTER_METADATA)) {
                    metadataFilters.add(filter);
                    continue;
                }
                // Any other field (fields an extension indexes itself): exact match on its
                // '.keyword' sub-field, as dynamically mapped strings get one.
                String fieldName = filter.field().endsWith(KEYWORD) ? filter.field() : filter.field() + KEYWORD;
                boolQueryBuilder.filter(f -> f.term(t -> t
                        .field(fieldName)
                        .value(FieldValue.of(filter.value()))
                ));
            }
        }
        if (metadataFilters.isEmpty()) {
            return Mono.just(boolQueryBuilder);
        }
        // Metadata keys match case-insensitively, but index field names do not: each key is
        // expanded to the spellings the documents actually use — one lookup for all the keys.
        Set<String> keys = new LinkedHashSet<>();
        metadataFilters.forEach(filter -> keys.add(metadataKey(filter.field())));
        return metadataKeySpellings(keys)
                .defaultIfEmpty(Map.of())
                .map(spellings -> {
                    for (FilterInput filter : metadataFilters) {
                        String key = metadataKey(filter.field());
                        addMetadataClause(spellings.getOrDefault(key, List.of(key)), filter.value(), boolQueryBuilder);
                    }
                    return boolQueryBuilder;
                });
    }

    /**
     * The spellings of the given metadata keys the indexed documents use, whatever their case
     * ({@code project} → {@code [project, Project]}), so that a metadata filter matches its key
     * case-insensitively; a key absent from the result is used as given. Default: none known
     * (see {@code IndexedMetadataKeys} for the index-backed answer).
     */
    default Mono<Map<String, List<String>>> metadataKeySpellings(Collection<String> keys) {
        return Mono.just(Map.of());
    }

    /** The metadata key of a {@code metadata.<key>} filter field (an explicit {@code .keyword} suffix dropped). */
    static String metadataKey(String field) {
        String key = field.substring(DocumentSearchUtil.FILTER_METADATA.length());
        return key.endsWith(KEYWORD) ? key.substring(0, key.length() - KEYWORD.length()) : key;
    }

    /**
     * The filters the database path understands too ({@link DocumentSearchUtil}), mapped onto the
     * index fields: most are plain keyword / date / long fields (no {@code .keyword} sub-field), and
     * some are not fields at all ({@code type}, date bounds, content-type patterns).
     *
     * @return false when the field is not one of them (the caller applies the generic clause)
     */
    static boolean addKnownFilterClause(FilterInput filter, BoolQuery.Builder boolQueryBuilder) {
        String value = filter.value().trim();
        switch (filter.field()) {
            case DocumentSearchUtil.FILTER_TYPE -> {
                // Folders are the documents indexed without an extension (files without a dot carry "")
                Query hasExtension = Query.of(q -> q.exists(e -> e.field(EXTENSION)));
                if (DocumentType.FOLDER.name().equalsIgnoreCase(value)) {
                    boolQueryBuilder.mustNot(hasExtension);
                } else {
                    boolQueryBuilder.filter(hasExtension);
                }
            }
            case DocumentSearchUtil.FILTER_CONTENT_TYPE -> addContentTypeClause(value, boolQueryBuilder);
            case DocumentSearchUtil.FILTER_EXTENSION -> {
                List<FieldValue> extensions = splitValues(value).stream()
                        .map(e -> e.startsWith(".") ? e.substring(1) : e)
                        .map(e -> FieldValue.of(e.toLowerCase(Locale.ROOT)))
                        .toList();
                if (!extensions.isEmpty()) {
                    boolQueryBuilder.filter(f -> f.terms(t -> t.field(EXTENSION).terms(v -> v.value(extensions))));
                }
            }
            case DocumentSearchUtil.FILTER_CREATED_BY, DocumentSearchUtil.FILTER_UPDATED_BY, DocumentSearchUtil.FILTER_PARENT_ID ->
                    boolQueryBuilder.filter(f -> f.term(t -> t.field(filter.field()).value(FieldValue.of(value))));
            case DocumentSearchUtil.FILTER_SIZE ->
                    boolQueryBuilder.filter(f -> f.term(t -> t.field(OpenSearchDocumentKey.size.toString())
                            .value(FieldValue.of(Long.parseLong(value)))));
            case DocumentSearchUtil.FILTER_CREATED_AT_AFTER -> addDateBound(OpenSearchDocumentKey.createdAt, value, true, boolQueryBuilder);
            case DocumentSearchUtil.FILTER_CREATED_AT_BEFORE -> addDateBound(OpenSearchDocumentKey.createdAt, value, false, boolQueryBuilder);
            case DocumentSearchUtil.FILTER_UPDATED_AT_AFTER -> addDateBound(OpenSearchDocumentKey.updatedAt, value, true, boolQueryBuilder);
            case DocumentSearchUtil.FILTER_UPDATED_AT_BEFORE -> addDateBound(OpenSearchDocumentKey.updatedAt, value, false, boolQueryBuilder);
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * A {@code metadata.<key>} filter over each spelling of the key (any of them matches), values
     * compared case-insensitively: a {@code term} on the keyword sub-field for an exact value, a
     * {@code wildcard} on it for a pattern ({@link MetadataCriteria}), an {@code exists} query on
     * the field for a bare wildcard ("the key is set", whatever its mapped type).
     * <p>
     * {@code metadata} is a dynamic object, so a key first indexed as a boolean or a number is
     * mapped {@code boolean} / {@code long} / {@code float}, with no keyword sub-field. A value
     * that spells one ({@code true}, {@code 42}, {@link MetadataCriteria#typedValue(String)}) is
     * therefore also matched on the field itself, through a lenient {@code match} (a typed field
     * of another type answers no hit instead of failing the search), and only where the document
     * has no keyword value: a string field keeps the exact keyword comparison, never the analysed one.
     */
    static void addMetadataClause(List<String> keys, String value, BoolQuery.Builder boolQueryBuilder) {
        boolean pattern = MetadataCriteria.isPattern(value);
        if (pattern && value.length() > MetadataCriteria.MAX_PATTERN_LENGTH) {
            throw new IllegalArgumentException("A metadata pattern cannot exceed " + MetadataCriteria.MAX_PATTERN_LENGTH + " characters");
        }
        String wildcard = pattern ? MetadataCriteria.toWildcardPattern(value) : null;
        boolean anyValue = wildcard != null && wildcard.chars().allMatch(c -> c == '*');
        String typed = pattern ? null : typedQueryText(value);
        List<Query> anyOf = new ArrayList<>();
        for (String key : keys) {
            String base = DocumentSearchUtil.FILTER_METADATA + key;
            String field = base + KEYWORD;
            if (anyValue) {
                anyOf.add(Query.of(q -> q.exists(e -> e.field(base))));
            } else if (pattern) {
                anyOf.add(Query.of(q -> q.wildcard(w -> w.field(field).value(wildcard).caseInsensitive(true))));
            } else {
                anyOf.add(Query.of(q -> q.term(t -> t.field(field).value(FieldValue.of(value)).caseInsensitive(true))));
                if (typed != null) {
                    anyOf.add(Query.of(q -> q.bool(b -> b
                            .mustNot(m -> m.exists(e -> e.field(field)))
                            .filter(f -> f.match(mq -> mq.field(base).query(FieldValue.of(typed)).lenient(true))))));
                }
            }
        }
        if (anyOf.size() == 1) {
            boolQueryBuilder.filter(anyOf.getFirst());
        } else {
            boolQueryBuilder.filter(f -> f.bool(b -> b.should(anyOf).minimumShouldMatch("1")));
        }
    }

    /**
     * The text a boolean / numeric field is queried with when an exact value spells a boolean or
     * a number: {@code true} / {@code false}, or the number in plain notation ({@code 1e3} →
     * {@code 1000}, {@code 42.0} → {@code 42}); {@code null} when the value spells neither.
     */
    static String typedQueryText(String value) {
        Object typed = MetadataCriteria.typedValue(value);
        if (typed instanceof Boolean b) {
            return b.toString();
        }
        if (typed instanceof java.math.BigDecimal number) {
            return number.signum() == 0 ? "0" : number.stripTrailingZeros().toPlainString();
        }
        return null;
    }

    /**
     * Content types: one or several comma-separated, each exact ({@code application/pdf}) or a
     * prefix ending with {@code %} ({@code image/%}) — the patterns of {@code ListFolderRequest.contentTypes}.
     * Documents indexed before {@code contentType} was mapped only carry their extension, so the
     * extensions of those content types match too.
     */
    static void addContentTypeClause(String value, BoolQuery.Builder boolQueryBuilder) {
        List<String> patterns = splitValues(value);
        if (patterns.isEmpty()) {
            return;
        }
        String field = OpenSearchDocumentKey.contentType.toString();
        List<Query> anyOf = new ArrayList<>();
        for (String pattern : patterns) {
            if (pattern.endsWith("%")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                anyOf.add(Query.of(q -> q.prefix(p -> p.field(field).value(prefix).caseInsensitive(true))));
            } else {
                anyOf.add(Query.of(q -> q.term(t -> t.field(field).value(FieldValue.of(pattern)).caseInsensitive(true))));
            }
        }
        List<FieldValue> extensions = ContentTypeMapper.extensionsMatching(patterns).stream().map(FieldValue::of).toList();
        if (!extensions.isEmpty()) {
            anyOf.add(Query.of(q -> q.bool(b -> b
                    .mustNot(m -> m.exists(e -> e.field(field)))
                    .filter(t -> t.terms(ts -> ts.field(EXTENSION).terms(v -> v.value(extensions)))))));
        }
        boolQueryBuilder.filter(f -> f.bool(b -> b.should(anyOf).minimumShouldMatch("1")));
    }

    /** {@code gte} / {@code lte} bound on a date field; an unparsable date filters nothing (as on the database path). */
    static void addDateBound(OpenSearchDocumentKey field, String value, boolean after, BoolQuery.Builder boolQueryBuilder) {
        String date;
        try {
            date = OffsetDateTime.parse(value).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException e) {
            return;
        }
        boolQueryBuilder.filter(f -> f.range(r -> {
            r.field(field.toString());
            return after ? r.gte(JsonData.of(date)) : r.lte(JsonData.of(date));
        }));
    }

    /** A filter value naming several keys: comma-separated, trimmed, blanks dropped. */
    static List<String> splitValues(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
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
