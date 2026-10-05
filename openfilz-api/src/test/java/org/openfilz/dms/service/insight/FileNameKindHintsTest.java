package org.openfilz.dms.service.insight;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FileNameKindHintsTest {

    private static final Set<String> KINDS = Set.copyOf(PropertiesCategoryTaxonomy.of(
            List.copyOf(CategoryTaxonomy.BUILT_IN_DESCRIPTIONS.keySet()), java.util.Map.of()).keys());

    @ParameterizedTest
    @CsvSource({
            "CNI DJIBI RECTO.JPG, id-document",
            "CNI DJIBI VERSO.JPG, id-document",
            "PHOTO ID DJIBI.JPG, id-document",
            "passeport_2025.pdf, id-document",
            "Permis-de-conduire scan.png, id-document",
            "Carte d'identité.jpeg, id-document",
            "Facture 2026-03 ACME.pdf, invoice",
            "invoice_F2026_0042.pdf, invoice",
            "Devis cuisine.pdf, quote",
            "contratDeBail.pdf, contract",
            "CV Jean Dupont.docx, cv",
            "Accu-Chek® 360° rapport 14 jours.pdf, report",
            "Accu-Chek® 360° report 17 au 30 aout.pdf, report",
            "budget 2026.xlsx, spreadsheet",
            "kickoff.pptx, presentation",
            "Factures 2026.xlsx, invoice",
            "cerfa_12345.pdf, form"
    })
    void namesItsKind(String fileName, String kind) {
        assertThat(FileNameKindHints.kindOf(fileName, KINDS)).contains(kind);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Djibi.jpg", "Carte SIM Djibi.jpg", "voeux_23-05-2022-172540.pdf", "e-Licence_Djibi_DEMEL_BC079708.jpeg",
            "Peugeot 308 130 CV.pdf", "recovery.pdf", "facture-et-contrat.pdf", "scan0001.pdf", ""
    })
    void noHintWhenTheNameDoesNotSayOrSaysTwoKinds(String fileName) {
        assertThat(FileNameKindHints.kindOf(fileName, KINDS)).isEmpty();
    }

    @Test
    void aKindTheDeploymentDoesNotClassifyIntoIsNoHint() {
        assertThat(FileNameKindHints.kindOf("CNI recto.jpg", Set.of("invoice", "report"))).isEmpty();
    }

    @Test
    void theDecoratorTrustsTheNameRefusesToGuessWithoutTextAndDelegatesTheRest() {
        AtomicInteger calls = new AtomicInteger();
        CategoryClassifier delegate = new CategoryClassifier() {
            @Override
            public String name() {
                return "prototype:test";
            }

            @Override
            public CategoryPrediction classify(UUID documentId, String fileName, String text) {
                calls.incrementAndGet();
                return new CategoryPrediction("report", 0.4, List.of());
            }
        };
        CategoryTaxonomy taxonomy = PropertiesCategoryTaxonomy.of(List.copyOf(CategoryTaxonomy.BUILT_IN_DESCRIPTIONS.keySet()),
                java.util.Map.of());
        FileNameAwareCategoryClassifier classifier = new FileNameAwareCategoryClassifier(delegate, taxonomy);

        assertThat(classifier.name()).isEqualTo("prototype:test+names");
        assertThat(FileNameAwareCategoryClassifier.namedWithoutRules(classifier.name())).isFalse();
        assertThat(FileNameAwareCategoryClassifier.namedWithoutRules("prototype:test")).isTrue();
        assertThat(FileNameAwareCategoryClassifier.namedWithoutRules("learned:knn")).isTrue();
        assertThat(FileNameAwareCategoryClassifier.namedWithoutRules("policy:prototype:test")).isFalse();
        assertThat(FileNameAwareCategoryClassifier.namedWithoutRules("user")).isFalse();
        assertThat(FileNameAwareCategoryClassifier.namedWithoutRules(null)).isFalse();
        assertThat(classifier.classify(null, "CNI recto.jpg", "").category()).isEqualTo("id-document");
        assertThat(classifier.classify(null, "Djibi.jpg", "").category()).isEqualTo(InsightResult.OTHER);
        assertThat(classifier.classify(null, "Djibi.jpg", "   ").category()).isEqualTo(InsightResult.OTHER);
        assertThat(calls).hasValue(0);
        assertThat(classifier.classify(null, "notes.pdf", "Quarterly figures and conclusions").category()).isEqualTo("report");
        assertThat(calls).hasValue(1);
    }
}
