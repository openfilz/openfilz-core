package org.openfilz.dms.service.filing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The folder-name table: a name per kind and language, read both ways, and the language of a library's folders. */
class CategoryFolderNamesTest {

    private final CategoryFolderNames names = new CategoryFolderNames();

    @Test
    @DisplayName("a kind has a folder name in every shipped language, English when the language is unknown, none for other")
    void nameOf() {
        assertThat(names.nameOf("invoice", "en")).contains("Invoices");
        assertThat(names.nameOf("invoice", "fr")).contains("Factures");
        assertThat(names.nameOf("invoice", "de")).contains("Rechnungen");
        assertThat(names.nameOf("Invoice", "FR-fr")).as("case and region tolerant").contains("Factures");
        assertThat(names.nameOf("invoice", "xx")).as("unknown language falls back to English").contains("Invoices");
        assertThat(names.nameOf("invoice", null)).contains("Invoices");
        assertThat(names.nameOf("other", "en")).isEmpty();
        assertThat(names.nameOf("payslip", "en")).as("a kind the table does not know").isEmpty();
        for (String language : List.of("en", "fr", "de", "es", "it", "nl", "pt", "ar")) {
            for (String kind : List.of("invoice", "quote", "contract", "report", "letter", "cv", "presentation", "spreadsheet",
                    "form", "id-document", "receipt", "minutes", "specification", "manual")) {
                assertThat(names.nameOf(kind, language)).as(kind + " in " + language).isPresent();
            }
        }
    }

    @Test
    @DisplayName("an existing folder name denotes a kind whatever its language, case, accents or number")
    void categoryOf() {
        assertThat(names.categoryOf("Invoices")).contains("invoice");
        assertThat(names.categoryOf("factures")).contains("invoice");
        assertThat(names.categoryOf("Facture")).as("singular alias").contains("invoice");
        assertThat(names.categoryOf("RECHNUNGEN")).contains("invoice");
        assertThat(names.categoryOf("Pieces d'identite")).as("accents stripped").contains("id-document");
        assertThat(names.categoryOf("Comptes-rendus")).contains("minutes");
        assertThat(names.categoryOf("Testing & Samples")).isEmpty();
        assertThat(names.categoryOf(null)).isEmpty();
    }

    @Test
    @DisplayName("the language of a library is the one most of its folder names are in; no match or a tie says nothing")
    void languageOf() {
        assertThat(names.languageOf(List.of("Contrats", "Rapports", "Clients", "2026"))).contains("fr");
        assertThat(names.languageOf(List.of("Contracts", "Reports", "Factures"))).contains("en");
        assertThat(names.languageOf(List.of("Clients", "2026", "_tst"))).isEmpty();
        assertThat(names.languageOf(List.of("Contrats", "Reports"))).as("a tie").isEmpty();
        assertThat(names.languageOf(List.of())).isEmpty();
    }

    @Test
    @DisplayName("deployment overrides win over the table and may add kinds and languages")
    void overrides() {
        CategoryFolderNames custom = new CategoryFolderNames(Map.of(
                "fr", Map.of("invoice", "Factures fournisseurs", "payslip", "Bulletins de paie"),
                "pl", Map.of("invoice", "Faktury")));
        assertThat(custom.nameOf("invoice", "fr")).contains("Factures fournisseurs");
        assertThat(custom.nameOf("payslip", "fr")).contains("Bulletins de paie");
        assertThat(custom.nameOf("invoice", "pl")).contains("Faktury");
        assertThat(custom.categoryOf("Faktury")).contains("invoice");
        assertThat(custom.categoryOf("Factures")).as("the built-in name still reads").contains("invoice");
        assertThat(custom.languageOf(List.of("Faktury", "Umowy"))).contains("pl");
    }
}
