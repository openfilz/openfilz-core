package org.openfilz.dms.service.insight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.service.insight.CategoryTaxonomy.Category;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The tier-2 system prompt: the marker the callers key on, the closed key list, and one described line per kind. */
class InsightPromptsTest {

    @Test
    @DisplayName("the marker and the JSON contract are kept; every kind is listed with its description and examples")
    void describesEveryKind() {
        List<Category> kinds = List.of(
                new Category("invoice", "A bill requesting payment. Facture, TVA.", List.of("facture-2026-03.pdf", "INV-0042")),
                new Category("payslip", "", List.of()),
                new Category("other", "Anything that fits none of the other kinds."));

        String prompt = InsightPrompts.system("INSIGHTS_TEST", kinds);

        assertThat(prompt).contains("(INSIGHTS_TEST)");
        assertThat(prompt).contains("\"category\": \"<one of: invoice, payslip, other>\"");
        assertThat(prompt).contains("\"summary\"").contains("\"keywords\"").contains("\"language\"").contains("\"entities\"");
        assertThat(prompt).contains("- invoice — A bill requesting payment. Facture, TVA. (e.g. facture-2026-03.pdf, INV-0042)");
        // A kind nobody described is its key alone — no dangling dash
        assertThat(prompt).contains("\n- payslip\n");
        assertThat(prompt).contains("- other — Anything that fits none of the other kinds.");
        assertThat(prompt).contains("\"other\" when none fits");
    }

    @Test
    @DisplayName("an empty taxonomy still yields a valid prompt over 'other' alone")
    void emptyTaxonomyIsOtherOnly() {
        String prompt = InsightPrompts.system("M", List.of());

        assertThat(prompt).contains("(M)").contains("<one of: other>").contains("- other — ");
        assertThat(InsightPrompts.system("M", null)).isEqualTo(prompt);
    }

    @Test
    @DisplayName("the user prompt names the file, its extension and content type before the text head")
    void userPrompt() {
        String prompt = InsightPrompts.user("Facture 42.PDF", "application/pdf", "Total TTC 1 200 EUR");

        assertThat(prompt).startsWith("File name: Facture 42.PDF\nType: pdf (application/pdf)")
                .endsWith("Text (beginning):\nTotal TTC 1 200 EUR");
        assertThat(InsightPrompts.user("notes", null, "x")).contains("Type: unknown\n");
    }
}
