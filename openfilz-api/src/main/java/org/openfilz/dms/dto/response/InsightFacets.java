package org.openfilz.dms.dto.response;

import java.util.List;

/**
 * The category and language facets of the library, from the document insights: how many active
 * files carry each tier-2 category and each detected language, largest first. The keys are what
 * {@code searchDocuments} accepts in its {@code category} / {@code language} filters.
 */
public record InsightFacets(List<Facet> categories, List<Facet> languages) {

    /** One facet value and how many files carry it. */
    public record Facet(String key, long count) {
    }

    public static InsightFacets empty() {
        return new InsightFacets(List.of(), List.of());
    }
}
