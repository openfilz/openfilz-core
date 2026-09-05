package org.openfilz.dms.service.insight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.config.AiProperties.Insights.Classifier.Mode;
import org.openfilz.dms.service.insight.CategoryClassifier.CategoryPrediction;
import org.openfilz.dms.service.insight.CategoryClassifier.CategoryPrediction.Scored;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The prototype classifier on a bag-of-words "embedding": nearest description wins, softmax confidence, one prototype pass. */
class PrototypeCategoryClassifierTest {

    private static final List<String> CATEGORIES = List.of("invoice", "contract", "report", "other");

    /** One dimension per kind, counting its words in any language; a floor so no vector is zero. */
    static final class BagOfWords implements EmbeddingModel {
        private static final List<List<String>> WORDS = List.of(
                List.of("invoice", "facture", "vat", "tva"),
                List.of("contract", "contrat", "clause"),
                List.of("report", "rapport", "findings"));
        final AtomicInteger batches = new AtomicInteger();

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            batches.incrementAndGet();
            List<Embedding> out = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                out.add(new Embedding(vector(request.getInstructions().get(i)), i));
            }
            return new EmbeddingResponse(out);
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        static float[] vector(String text) {
            String lower = text.toLowerCase(Locale.ROOT);
            float[] v = new float[WORDS.size()];
            for (int i = 0; i < WORDS.size(); i++) {
                v[i] = 0.05f;
                for (String word : WORDS.get(i)) {
                    int at = 0;
                    while ((at = lower.indexOf(word, at)) >= 0) {
                        v[i] += 1;
                        at += word.length();
                    }
                }
            }
            return v;
        }
    }

    private static AiProperties.Insights.Classifier config() {
        return new AiProperties.Insights.Classifier();
    }

    @Test
    @DisplayName("the nearest prototype names the category, in any language, with the runners-up")
    void nearestPrototypeWins() {
        BagOfWords model = new BagOfWords();
        PrototypeCategoryClassifier classifier = new PrototypeCategoryClassifier(model, "bag", CATEGORIES, config());

        CategoryPrediction invoice = classifier.classify("facture-2026-03.pdf", "Facture n° F-12 — montant TTC, TVA 20 %");
        assertThat(invoice.category()).isEqualTo("invoice");
        assertThat(invoice.confidence()).isGreaterThan(0.9);
        assertThat(invoice.alternatives()).extracting(Scored::category).startsWith("invoice").hasSize(3);

        CategoryPrediction report = classifier.classify("q3.pdf", "Quarterly report: findings and figures");
        assertThat(report.category()).isEqualTo("report");
        assertThat(classifier.name()).isEqualTo("prototype:bag");
        assertThat(classifier.categories()).containsExactly("invoice", "contract", "report");
    }

    @Test
    @DisplayName("the prototypes are embedded once, in one batch, whatever the number of documents")
    void prototypesEmbeddedOnce() {
        BagOfWords model = new BagOfWords();
        PrototypeCategoryClassifier classifier = new PrototypeCategoryClassifier(model, "bag", CATEGORIES, config());
        classifier.classify("a.txt", "invoice");
        classifier.classify("b.txt", "contract");
        classifier.classify("c.txt", "report");
        // one batch for the three prototypes + one call per document
        assertThat(model.batches.get()).isEqualTo(1 + 3);
    }

    @Test
    @DisplayName("an override replaces the built-in description; a listed category without one is still placed by its name")
    void overridesAndUnknownCategories() {
        AiProperties.Insights.Classifier config = config();
        config.setPrototypes(Map.of("contract", "Only the word clause clause clause"));
        Map<String, String> prototypes = PrototypeCategoryClassifier.prototypes(List.of("Invoice", "contract", "payslip", "other"), config.getPrototypes());
        assertThat(prototypes).containsKeys("invoice", "contract", "payslip").doesNotContainKey("other");
        assertThat(prototypes.get("contract")).startsWith("Only the word clause");
        assertThat(prototypes.get("invoice")).isEqualTo(PrototypeCategoryClassifier.DEFAULT_PROTOTYPES.get("invoice"));
        assertThat(prototypes.get("payslip")).isEqualTo("payslip");
    }

    @Test
    @DisplayName("below the similarity floor nothing fits: other")
    void similarityFloor() {
        AiProperties.Insights.Classifier config = config();
        config.setMinSimilarity(0.999);
        PrototypeCategoryClassifier classifier = new PrototypeCategoryClassifier(new BagOfWords(), "bag", CATEGORIES, config);
        CategoryPrediction prediction = classifier.classify("x.txt", "nothing of the kinds we know");
        assertThat(prediction.category()).isEqualTo("other");
        assertThat(prediction.alternatives()).hasSize(3);
    }

    @Test
    @DisplayName("the confidence is the softmax share of the best: a close runner-up halves it, a lower temperature sharpens it")
    void confidenceIsSoftmax() {
        List<Scored> clear = List.of(new Scored("invoice", 0.90), new Scored("report", 0.60), new Scored("contract", 0.55));
        List<Scored> tight = List.of(new Scored("invoice", 0.90), new Scored("report", 0.89), new Scored("contract", 0.55));
        assertThat(PrototypeCategoryClassifier.decide(clear, 0.05, 0).confidence()).isGreaterThan(0.99);
        double tightAt05 = PrototypeCategoryClassifier.decide(tight, 0.05, 0).confidence();
        assertThat(tightAt05).isBetween(0.5, 0.6);
        assertThat(PrototypeCategoryClassifier.decide(tight, 0.01, 0).confidence()).isGreaterThan(tightAt05);
        assertThat(PrototypeCategoryClassifier.decide(List.of(), 0.05, 0).category()).isEqualTo("other");
    }

    @Test
    @DisplayName("the text head is bounded and prefixed, the file name leads")
    void inputShape() {
        AiProperties.Insights.Classifier config = config();
        config.setMaxChars(200);
        config.setPrefix("classification: ");
        PrototypeCategoryClassifier classifier = new PrototypeCategoryClassifier(new BagOfWords(), "bag", CATEGORIES, config);
        String input = classifier.input("f.pdf", "x".repeat(1000));
        assertThat(input).startsWith("classification: File name: f.pdf\n").hasSize("classification: File name: f.pdf\n".length() + 200);
        assertThat(classifier.input(null, null)).isEqualTo("classification: ");
    }

    @Test
    @DisplayName("a local verdict is final in prototype mode, when sure in auto mode, or when the model budget is spent")
    void acceptLocal() {
        assertThat(AiDocumentInsightService.acceptLocal(Mode.PROTOTYPE, 0.1, 0.6, true)).isTrue();
        assertThat(AiDocumentInsightService.acceptLocal(Mode.AUTO, 0.7, 0.6, true)).isTrue();
        assertThat(AiDocumentInsightService.acceptLocal(Mode.AUTO, 0.5, 0.6, true)).isFalse();
        assertThat(AiDocumentInsightService.acceptLocal(Mode.AUTO, 0.5, 0.6, false)).as("no model call left today").isTrue();
        assertThat(AiDocumentInsightService.acceptLocal(Mode.LLM, 1.0, 0.6, true)).as("never consulted in llm mode").isFalse();
    }
}
