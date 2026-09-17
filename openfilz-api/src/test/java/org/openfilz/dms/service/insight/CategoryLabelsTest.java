package org.openfilz.dms.service.insight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Category display names: the caller's language, English as the fallback, the key when nobody named the kind. */
class CategoryLabelsTest {

    @Test
    @DisplayName("the language is the first supported one among the candidates, an Accept-Language header read by weight; English otherwise")
    void resolvesTheLanguage() {
        assertThat(CategoryLabels.resolveLanguage("fr")).isEqualTo("fr");
        assertThat(CategoryLabels.resolveLanguage("pt-BR")).isEqualTo("pt");
        assertThat(CategoryLabels.resolveLanguage("ja-JP,de;q=0.4,es;q=0.9")).isEqualTo("es");
        assertThat(CategoryLabels.resolveLanguage("ja", "nl")).isEqualTo("nl");
        assertThat(CategoryLabels.resolveLanguage(null, "", "de_AT")).isEqualTo("de");
        assertThat(CategoryLabels.resolveLanguage("not a;;header")).isEqualTo("en");
        assertThat(CategoryLabels.resolveLanguage()).isEqualTo("en");
        assertThat(CategoryLabels.resolveLanguage((String[]) null)).isEqualTo("en");
    }

    @Test
    @DisplayName("a label resolves to the language, else English, else the key")
    void resolvesALabel() {
        Map<String, String> labels = Map.of("en", "Purchase order", "fr", "Bon de commande");

        assertThat(CategoryLabels.resolve(labels, "fr", "purchase-order")).isEqualTo("Bon de commande");
        assertThat(CategoryLabels.resolve(labels, "de", "purchase-order")).isEqualTo("Purchase order");
        assertThat(CategoryLabels.resolve(Map.of("fr", "Bulletin"), "de", "payslip")).isEqualTo("payslip");
        assertThat(CategoryLabels.resolve(null, "fr", "payslip")).isEqualTo("payslip");
    }

    @Test
    @DisplayName("a stored map keeps the supported languages only, lower case, trimmed, in display order, blanks dropped")
    void normalises() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("PT", " Ordem de compra ");
        raw.put("en", "Purchase order");
        raw.put("fr", "  ");
        raw.put("ja", "注文書");
        raw.put(null, "nobody");

        assertThat(CategoryLabels.normalise(raw)).containsExactly(Map.entry("en", "Purchase order"), Map.entry("pt", "Ordem de compra"));
    }

    @Test
    @DisplayName("every built-in kind ships a label in every language, and an override replaces only its own languages")
    void builtInAndMerge() {
        CategoryLabels.builtIn().forEach((key, labels) ->
                assertThat(labels.keySet()).as(key).containsExactlyElementsOf(CategoryLabels.LANGUAGES));
        assertThat(CategoryLabels.builtIn().keySet())
                .containsExactlyInAnyOrderElementsOf(new org.openfilz.dms.config.AiProperties.Insights().getCategories());

        Map<String, String> merged = CategoryLabels.merge(CategoryLabels.builtIn("invoice"), Map.of("fr", "Facture fournisseur"));
        assertThat(merged).containsEntry("fr", "Facture fournisseur").containsEntry("de", "Rechnung").hasSize(8);
    }
}
