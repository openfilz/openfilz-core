package org.openfilz.dms.service.insight;

import org.openfilz.dms.entity.Document;
import reactor.core.publisher.Mono;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Who may be enriched, and by what. The governance seam in front of the tier-2 enrichment and
 * smart filing: the core permits everything ({@link PermitAllInsightsPolicy}); an extension
 * registers a {@code @Primary} implementation that answers per organisation, group or document —
 * "tier 2 is off for this tenant", "identity documents never reach a third-party model",
 * "smart filing is disabled for this team".
 * <p>
 * Consulted on the worker thread, never on the upload path, so an implementation may read the
 * database. Reactive on purpose: the answer usually needs a lookup.
 */
public interface InsightsPolicy {

    /**
     * Prefix of the {@code model} column when the local classifier screened the document on the
     * policy's behalf and its kind was kept away from the model: {@code policy:<classifier name>}.
     */
    String SCREENED_MODEL_PREFIX = "policy:";

    /**
     * What the policy allows for one document.
     *
     * @param enrichmentAllowed false = the document is not enriched at all (its row is SKIPPED with the reason)
     * @param modelAllowed      false = no model may read this document's text; only a local classifier may
     *                          name its category, whatever the configured mode
     * @param blockedCategories kinds whose text must never reach a (third-party) model; empty = none. When a
     *                          model call would happen, the local classifier pre-classifies the document and a
     *                          blocked verdict is stored as a category-only row instead
     * @param reason            why, for the row's error column and the log; may be null
     */
    record Verdict(boolean enrichmentAllowed, boolean modelAllowed, Set<String> blockedCategories, String reason) {

        public Verdict {
            // Categories are stored lower case with hyphens; a policy written as "ID Document" must still match
            Set<String> normalised = new LinkedHashSet<>();
            if (blockedCategories != null) {
                for (String category : blockedCategories) {
                    if (category != null && !category.isBlank()) {
                        normalised.add(normalise(category));
                    }
                }
            }
            blockedCategories = Set.copyOf(normalised);
        }

        public static Verdict permitAll() {
            return new Verdict(true, true, Set.of(), null);
        }

        /** Whether this kind may not be sent to a model. */
        public boolean blocks(String category) {
            return category != null && blockedCategories.contains(normalise(category));
        }

        private static String normalise(String category) {
            return category.trim().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
        }
    }

    /** Consulted before a document is enriched (reactive; called on the worker, never on the upload path). */
    default Mono<Verdict> forDocument(Document document) {
        return Mono.just(Verdict.permitAll());
    }

    /** Whether smart filing may act for this user at all (an organisation-level switch). */
    default Mono<Boolean> autoFileAllowed(String userEmail) {
        return Mono.just(true);
    }
}
