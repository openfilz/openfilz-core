package org.openfilz.dms.service.insight;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.e2e.AiTestConfig;
import org.openfilz.dms.entity.AiDocumentInsight;
import org.openfilz.dms.service.insight.CategoryClassifier.CategoryPrediction;
import org.openfilz.dms.service.insight.LearnedCategoryClassifier.Source;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The learned classifier: the nearest labelled documents vote, the document never votes for itself, the cold start answers when they are too few. */
class LearnedCategoryClassifierTest {

    private final AiTestConfig.BagOfWordsEmbeddingModel embeddings = new AiTestConfig.BagOfWordsEmbeddingModel();
    private final VectorStore vectorStore = SimpleVectorStore.builder(embeddings).build();
    private final Map<UUID, AiDocumentInsight> rows = new LinkedHashMap<>();
    private final DocumentInsightStore store = mock(DocumentInsightStore.class);
    private final List<String> coldStartCalls = new ArrayList<>();
    private final CategoryClassifier coldStart = new CategoryClassifier() {
        @Override
        public String name() {
            return "prototype:test";
        }

        @Override
        public CategoryPrediction classify(UUID documentId, String fileName, String text) {
            coldStartCalls.add(fileName);
            return new CategoryPrediction("cold", 0.42, List.of());
        }
    };

    @BeforeEach
    void wireStore() {
        when(store.findAll(any())).thenAnswer(invocation -> {
            Collection<UUID> ids = invocation.getArgument(0);
            return Flux.fromIterable(ids.stream().map(rows::get).filter(r -> r != null).toList());
        });
    }

    private UUID seed(String text, String category, String model) {
        UUID id = UUID.randomUUID();
        vectorStore.add(List.of(new Document(text, Map.of("document_id", id.toString()))));
        if (category != null) {
            rows.put(id, AiDocumentInsight.builder().documentId(id).category(category).model(model)
                    .status(AiDocumentInsight.STATUS_DONE).tier(2).build());
        }
        return id;
    }

    private LearnedCategoryClassifier classifier(AiProperties.Insights.Classifier config) {
        @SuppressWarnings("unchecked")
        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(vectorStore);
        return new LearnedCategoryClassifier(provider, store, coldStart, config);
    }

    @Test
    @DisplayName("the nearest labelled documents vote, weighted by similarity; the model's and the user's labels teach")
    void neighboursVote() {
        for (int i = 0; i < 4; i++) seed("Invoice F-2026-00" + i + " from Globex, amount due, VAT.", "invoice", "ollama:qwen2.5");
        for (int i = 0; i < 4; i++) seed("Monthly report " + i + " of Globex, figures and outlook.", "report", "user");
        LearnedCategoryClassifier classifier = classifier(new AiProperties.Insights.Classifier());

        CategoryPrediction invoice = classifier.classify(null, "new.txt", "Invoice F-2026-0099 from Globex, amount due, VAT.");
        assertThat(invoice.category()).isEqualTo("invoice");
        assertThat(invoice.confidence()).isGreaterThan(0.5);
        assertThat(invoice.alternatives()).extracting(CategoryPrediction.Scored::category).startsWith("invoice");
        assertThat(classifier.name()).isEqualTo("learned:knn");

        CategoryPrediction report = classifier.classify(null, "new.txt", "Monthly report 99 of Globex, figures and outlook.");
        assertThat(report.category()).isEqualTo("report");
        assertThat(coldStartCalls).isEmpty();
    }

    @Test
    @DisplayName("a document never teaches itself, and labels of the excluded sources do not count")
    void selfAndSourcesAreExcluded() {
        UUID self = seed("Invoice F-2026-0500 from Globex, amount due.", "invoice", "ollama:qwen2.5");
        // Prototype-labelled twins: not in learn-from, so they do not vote
        for (int i = 0; i < 3; i++) seed("Invoice F-2026-050" + i + " from Globex, amount due.", "invoice", "prototype:nomic");
        AiProperties.Insights.Classifier config = new AiProperties.Insights.Classifier();
        config.getLearned().setMinNeighbours(1);
        LearnedCategoryClassifier classifier = classifier(config);

        CategoryPrediction prediction = classifier.classify(self, "self.txt", "Invoice F-2026-0500 from Globex, amount due.");
        assertThat(prediction.category()).as("nobody eligible voted: cold start").isEqualTo("cold");
        assertThat(coldStartCalls).containsExactly("self.txt");

        config.getLearned().setLearnFrom(List.of(Source.MODEL, Source.USER, Source.PROTOTYPE));
        CategoryPrediction withPrototypes = classifier(config).classify(self, "self.txt", "Invoice F-2026-0500 from Globex, amount due.");
        assertThat(withPrototypes.category()).isEqualTo("invoice");
    }

    @Test
    @DisplayName("too few labelled neighbours, or a split vote, hand over to the cold start")
    void coldStartWhenUnsure() {
        seed("Invoice F-2026-0600 from Globex, amount due.", "invoice", "user");
        seed("Invoice F-2026-0601 from Globex, amount due.", null, null);   // embedded, not labelled
        AiProperties.Insights.Classifier config = new AiProperties.Insights.Classifier();
        LearnedCategoryClassifier classifier = classifier(config);
        assertThat(classifier.classify(null, "few.txt", "Invoice F-2026-0699 from Globex, amount due.").category()).isEqualTo("cold");

        // Three labelled neighbours split three ways: no majority
        seed("Invoice F-2026-0602 from Globex, amount due.", "receipt", "user");
        seed("Invoice F-2026-0603 from Globex, amount due.", "quote", "user");
        CategoryPrediction split = classifier.classify(null, "split.txt", "Invoice F-2026-0699 from Globex, amount due.");
        assertThat(split.category()).isEqualTo("cold");

        config.getLearned().setMinConfidence(0.3);
        CategoryPrediction lenient = classifier(config).classify(null, "split.txt", "Invoice F-2026-0699 from Globex, amount due.");
        assertThat(lenient.category()).isIn("invoice", "receipt", "quote");
        assertThat(lenient.alternatives()).hasSize(3);
    }

    @Test
    @DisplayName("who wrote a label is read from the model column")
    void sources() {
        assertThat(Source.of("user")).isEqualTo(Source.USER);
        assertThat(Source.of("prototype:nomic-embed-text")).isEqualTo(Source.PROTOTYPE);
        assertThat(Source.of("learned:knn")).isEqualTo(Source.LEARNED);
        assertThat(Source.of("google-genai:gemini-2.5-flash")).isEqualTo(Source.MODEL);
        assertThat(Source.of(null)).isEqualTo(Source.MODEL);
    }
}
