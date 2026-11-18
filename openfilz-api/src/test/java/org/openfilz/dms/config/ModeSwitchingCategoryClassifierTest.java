package org.openfilz.dms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AiProperties.Insights.Classifier.Mode;
import org.openfilz.dms.service.insight.CategoryClassifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The category classifier bean follows {@code classifier.mode} at each call. */
class ModeSwitchingCategoryClassifierTest {

    private static CategoryClassifier answering(String name, String category) {
        return new CategoryClassifier() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public CategoryPrediction classify(UUID documentId, String fileName, String text) {
                return new CategoryPrediction(category, 0.9, List.of());
            }
        };
    }

    @Test
    @DisplayName("prototype and llm use the descriptions, learned and auto the learned classifier, switched per call")
    void picksPerCall() {
        AiProperties.Insights.Classifier config = new AiProperties.Insights.Classifier();
        DocumentInsightConfig.ModeSwitchingCategoryClassifier classifier = new DocumentInsightConfig.ModeSwitchingCategoryClassifier(
                config, answering("prototype:x", "invoice"), answering("learned:knn", "contract"));

        config.setMode(Mode.PROTOTYPE);
        assertThat(classifier.name()).isEqualTo("prototype:x");
        assertThat(classifier.classify("a.pdf", "text").category()).isEqualTo("invoice");

        config.setMode(Mode.LEARNED);
        assertThat(classifier.name()).isEqualTo("learned:knn");
        assertThat(classifier.classify("a.pdf", "text").category()).isEqualTo("contract");

        config.setMode(Mode.AUTO);
        assertThat(classifier.name()).isEqualTo("learned:knn");

        config.setMode(Mode.LLM);
        assertThat(classifier.name()).isEqualTo("prototype:x");
    }

    @Test
    @DisplayName("no insight store: learned and auto fall back to the descriptions")
    void withoutLearned() {
        AiProperties.Insights.Classifier config = new AiProperties.Insights.Classifier();
        config.setMode(Mode.AUTO);
        DocumentInsightConfig.ModeSwitchingCategoryClassifier classifier = new DocumentInsightConfig.ModeSwitchingCategoryClassifier(
                config, answering("prototype:x", "invoice"), null);
        assertThat(classifier.name()).isEqualTo("prototype:x");
    }
}
