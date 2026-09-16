package org.openfilz.dms.service.insight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.service.insight.CategoryTaxonomy.Category;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The core taxonomy over the properties: normalised keys, {@code other} always last, built-in descriptions unless overridden. */
class PropertiesCategoryTaxonomyTest {

    @Test
    @DisplayName("keys are normalised (lower case, hyphens), de-duplicated, and 'other' closes the list wherever the property puts it")
    void normalisesAndOrders() {
        CategoryTaxonomy taxonomy = PropertiesCategoryTaxonomy.of(
                List.of("other", "Invoice", " ID Document ", "id_document", "Contract", "", "INVOICE"), null);

        assertThat(taxonomy.keys()).containsExactly("invoice", "id-document", "contract", InsightResult.OTHER);
        assertThat(taxonomy.categories().getLast().key()).isEqualTo(InsightResult.OTHER);
    }

    @Test
    @DisplayName("'other' is present even when the property omits it; an empty property falls back to the built-in list")
    void otherIsAlwaysThere() {
        assertThat(PropertiesCategoryTaxonomy.of(List.of("invoice"), null).keys()).containsExactly("invoice", InsightResult.OTHER);

        CategoryTaxonomy defaults = PropertiesCategoryTaxonomy.of(List.of(), null);
        assertThat(defaults.keys()).containsExactlyElementsOf(new AiProperties.Insights().getCategories());
        assertThat(defaults.keys().getLast()).isEqualTo(InsightResult.OTHER);
    }

    @Test
    @DisplayName("built-in kinds carry their description; a kind nobody described has an empty one; an override wins")
    void describes() {
        CategoryTaxonomy taxonomy = PropertiesCategoryTaxonomy.of(List.of("invoice", "payslip", "report"),
                Map.of("report", "A weekly status report for the board."));

        Category invoice = taxonomy.find("invoice").orElseThrow();
        assertThat(invoice.description()).isEqualTo(CategoryTaxonomy.BUILT_IN_DESCRIPTIONS.get("invoice")).contains("Facture");
        assertThat(taxonomy.find("payslip").orElseThrow().description()).isEmpty();
        assertThat(taxonomy.find("report").orElseThrow().description()).isEqualTo("A weekly status report for the board.");
        assertThat(taxonomy.find(InsightResult.OTHER).orElseThrow().description()).isNotEmpty();
    }

    @Test
    @DisplayName("find() normalises what the user typed and answers empty for a kind the taxonomy has not")
    void findNormalises() {
        CategoryTaxonomy taxonomy = PropertiesCategoryTaxonomy.of(List.of("invoice", "id-document"), null);

        assertThat(taxonomy.find("ID Document")).map(Category::key).contains("id-document");
        assertThat(taxonomy.find(" Invoice ")).map(Category::key).contains("invoice");
        assertThat(taxonomy.find("Other")).map(Category::key).contains(InsightResult.OTHER);
        assertThat(taxonomy.find("poem")).isEmpty();
        assertThat(taxonomy.find(null)).isEmpty();
        assertThat(taxonomy.find("  ")).isEmpty();
    }

    @Test
    @DisplayName("the properties bean reads the list and the prototype overrides on every call")
    void readsProperties() {
        AiProperties properties = new AiProperties();
        properties.getInsights().setCategories(new java.util.ArrayList<>(List.of("Invoice", "report")));
        properties.getInsights().getClassifier().getPrototypes().put("invoice", "Facture fournisseur.");
        PropertiesCategoryTaxonomy taxonomy = new PropertiesCategoryTaxonomy(properties);

        assertThat(taxonomy.keys()).containsExactly("invoice", "report", InsightResult.OTHER);
        assertThat(taxonomy.find("invoice").orElseThrow().description()).isEqualTo("Facture fournisseur.");

        // Not cached: a change to the source is seen by the next call
        properties.getInsights().getCategories().add("contract");
        assertThat(taxonomy.keys()).containsExactly("invoice", "report", "contract", InsightResult.OTHER);
    }
}
