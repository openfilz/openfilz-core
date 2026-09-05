package org.openfilz.dms.service.insight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The tier-2 answer contract: tolerant of fences and casing, strict on the category list, never a half result. */
class InsightResultTest {

    private static final List<String> CATEGORIES = List.of("invoice", "contract", "report", "other");

    @Test
    @DisplayName("a fenced JSON answer is parsed and normalised")
    void parsesFencedJson() {
        InsightResult result = InsightResult.parse("""
                ```json
                {"category": "Invoice", "summary": "Facture ACME de mars.", "keywords": ["facture", "ACME", "facture"],
                 "language": "fr-FR", "entities": {"client": "ACME", "invoice_number": "F-2026-0042", "nested": {"x": 1}}}
                ```""", CATEGORIES);

        assertThat(result.category()).isEqualTo("invoice");
        assertThat(result.summary()).isEqualTo("Facture ACME de mars.");
        assertThat(result.keywords()).containsExactly("facture", "ACME");
        assertThat(result.language()).isEqualTo("fr");
        assertThat(result.entities()).containsEntry("client", "ACME").containsEntry("invoice_number", "F-2026-0042")
                .doesNotContainKey("nested");
    }

    @Test
    @DisplayName("an unknown category becomes 'other'; prose around the JSON is ignored")
    void unknownCategoryIsOther() {
        InsightResult result = InsightResult.parse(
                "Here is the result: {\"category\": \"poem\", \"summary\": \"x\"} hope it helps", CATEGORIES);
        assertThat(result.category()).isEqualTo(InsightResult.OTHER);
        assertThat(result.keywords()).isEmpty();
        assertThat(result.entities()).isEmpty();
        assertThat(result.language()).isNull();
    }

    @Test
    @DisplayName("over-long fields are cut to the column widths")
    void truncatesLongFields() {
        String answer = "{\"category\":\"report\",\"summary\":\"" + "s".repeat(700) + "\",\"keywords\":["
                + String.join(",", java.util.Collections.nCopies(20, "\"k\"").stream()
                        .map(k -> k).toList()).replace("\"k\"", "\"k\"") + "]}";
        InsightResult result = InsightResult.parse(answer, CATEGORIES);
        assertThat(result.summary()).hasSize(InsightResult.MAX_SUMMARY);
        assertThat(result.keywords()).hasSizeLessThanOrEqualTo(InsightResult.MAX_KEYWORDS);
    }

    @Test
    @DisplayName("no JSON object means a rejected answer, not a half row")
    void rejectsNonJson() {
        assertThatThrownBy(() -> InsightResult.parse("I cannot do that.", CATEGORIES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InsightResult.parse("{not json", CATEGORIES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InsightResult.parse("   ", CATEGORIES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InsightResult.parse("[1,2]", CATEGORIES))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
