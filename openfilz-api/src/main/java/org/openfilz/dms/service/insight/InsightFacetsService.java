package org.openfilz.dms.service.insight;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.response.InsightFacets;
import org.openfilz.dms.dto.response.InsightFacets.Facet;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * The search facets the insights offer: how many active files carry each tier-2 category and
 * each detected language. One grouped query over {@code ai_document_insights} joined to the
 * active {@code FILE} documents, both facets in one round trip.
 * <p>
 * The core counts the whole library. An extension that scopes documents per user appends its
 * joins in {@link #facetJoins(String)} and binds what they reference in
 * {@link #bindFacetUserContext} — the same seam as the insight store's backfill query. Always a
 * bean, never a bean condition: the AI switch is checked per request by the controller.
 */
@Service
@RequiredArgsConstructor
@ImportRuntimeHints(InsightFacetsRuntimeHints.class)
public class InsightFacetsService {

    static final String FACET_CATEGORY = "category";
    static final String FACET_LANGUAGE = "language";

    /**
     * Both facets in one statement: the first placeholder is the extension's joins, the second
     * its predicate (each may be empty). A NULL category or language is not a facet value.
     */
    private static final String FACETS = """
            SELECT 'category' AS facet, i.category AS key, count(*) AS n
              FROM ai_document_insights i
              JOIN documents d ON d.id = i.document_id%1$s
             WHERE d.type = 'FILE' AND d.active = true AND i.category IS NOT NULL%2$s
             GROUP BY i.category
            UNION ALL
            SELECT 'language' AS facet, i.language AS key, count(*) AS n
              FROM ai_document_insights i
              JOIN documents d ON d.id = i.document_id%1$s
             WHERE d.type = 'FILE' AND d.active = true AND i.language IS NOT NULL%2$s
             GROUP BY i.language
             ORDER BY facet, n DESC, key""";

    private final DatabaseClient databaseClient;

    /**
     * @param userEmail the caller, for an extension that scopes the counts (the core counts the library)
     */
    public Mono<InsightFacets> facets(String userEmail) {
        String sql = FACETS.formatted(facetJoins(userEmail), facetPredicate(userEmail));
        return bindFacetUserContext(databaseClient.sql(sql), userEmail)
                .flatMapMany(spec -> spec.map(row -> new Row(
                        row.get("facet", String.class),
                        row.get("key", String.class),
                        row.get("n", Long.class))).all())
                .collectList()
                .map(InsightFacetsService::toFacets);
    }

    /**
     * Joins appended right after {@code JOIN documents d ON ...} in both halves of the facet
     * query — the core has no ownership and appends nothing; an extension joins its ownership or
     * share tables here (start with a space).
     */
    protected String facetJoins(String userEmail) {
        return "";
    }

    /**
     * Predicate appended to both {@code WHERE} clauses (start with {@code " AND "}) — the core
     * appends nothing; an extension restricts to what the caller may see.
     */
    protected String facetPredicate(String userEmail) {
        return "";
    }

    /** Binds what {@link #facetJoins} / {@link #facetPredicate} reference; the core binds nothing. Reactive: never block here. */
    protected Mono<DatabaseClient.GenericExecuteSpec> bindFacetUserContext(DatabaseClient.GenericExecuteSpec spec, String userEmail) {
        return Mono.just(spec);
    }

    private record Row(String facet, String key, Long count) {
    }

    private static InsightFacets toFacets(List<Row> rows) {
        List<Facet> categories = new ArrayList<>();
        List<Facet> languages = new ArrayList<>();
        for (Row row : rows) {
            if (row.key() == null || row.count() == null) {
                continue;
            }
            Facet facet = new Facet(row.key(), row.count());
            if (FACET_CATEGORY.equals(row.facet())) {
                categories.add(facet);
            } else if (FACET_LANGUAGE.equals(row.facet())) {
                languages.add(facet);
            }
        }
        return new InsightFacets(List.copyOf(categories), List.copyOf(languages));
    }
}
