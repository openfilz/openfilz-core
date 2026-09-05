package org.openfilz.dms.service.insight;

import java.util.List;
import java.util.UUID;

/**
 * Names the kind of a document ({@code invoice}, {@code contract}, …) from its text, without
 * being a language model. The seam behind the tier-2 category: the enrichment worker consults
 * it first when {@code openfilz.ai.insights.classifier.mode} is {@code prototype} or
 * {@code auto}, and only asks the chat model for the documents it is unsure about — or never.
 * Implementations are blocking (they run on the worker's boundedElastic thread) and must be
 * safe to call concurrently.
 */
public interface CategoryClassifier {

    /** A stable label for logs and the row's {@code model} column, e.g. {@code prototype:nomic-embed-text}. */
    String name();

    /**
     * @param documentId the document being classified (null for text that is no document yet), so a
     *                   classifier that learns from the library never lets a document teach itself
     * @param fileName   the document's name, a strong hint for many kinds ("facture-2026-03.pdf")
     * @param text       the head of the document's text (the caller bounds it)
     * @return the best category with the classifier's own confidence in it, never null
     */
    CategoryPrediction classify(UUID documentId, String fileName, String text);

    /** Text that is no stored document yet. */
    default CategoryPrediction classify(String fileName, String text) {
        return classify(null, fileName, text);
    }

    /** One category and how sure the classifier is, with the runners-up for the record. */
    record CategoryPrediction(String category, double confidence, List<Scored> alternatives) {

        public CategoryPrediction {
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        }

        /** A category and its raw score (a cosine similarity for the prototype classifier). */
        public record Scored(String category, double score) {
        }
    }
}
