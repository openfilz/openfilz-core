package org.openfilz.dms.config;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.service.insight.CategoryClassifier;
import org.openfilz.dms.service.insight.DocumentInsightService;
import org.openfilz.dms.service.insight.PrototypeCategoryClassifier;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 * Runtime selection of the tier-2 insight service (native-safe: both implementations are in
 * the image, the property is read at startup, the unused one is never initialised).
 */
@Slf4j
@Configuration
public class DocumentInsightConfig {

    @Bean
    @Primary
    public DocumentInsightService documentInsightService(
            AiProperties aiProperties,
            @Lazy @Qualifier("aiDocumentInsightService") DocumentInsightService aiService,
            @Lazy @Qualifier("noOpDocumentInsightService") DocumentInsightService noOpService) {
        boolean active = aiProperties.isActive() && aiProperties.getInsights().isActive();
        if (aiProperties.getInsights().isActive() && !aiProperties.isActive()) {
            log.warn("openfilz.ai.insights.active is on but openfilz.ai.active is off — document insights stay off");
        }
        log.info("Document insights (tier 2, AI enrichment): {}", active ? "ENABLED" : "disabled");
        if (active) {
            log.info("Document insights category classifier: {}", aiProperties.getInsights().getClassifier().getMode());
        }
        return active ? aiService : noOpService;
    }

    /**
     * The prototype category classifier on the deployment's embedding model. Lazy: built on the
     * first tier-2 enrichment in {@code prototype} / {@code auto} mode, never in {@code llm} mode;
     * without an embedding model it exists but every classification fails with a clear message.
     */
    @Bean
    @Lazy
    public CategoryClassifier prototypeCategoryClassifier(AiProperties aiProperties, Environment environment,
                                                          ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        String provider = environment.getProperty("spring.ai.model.embedding", "");
        String modelName = provider.isBlank() ? "" : environment.getProperty("spring.ai." + provider + ".embedding.model", provider);
        if (embeddingModel == null) {
            log.warn("openfilz.ai.insights.classifier.mode is {} but no embedding model is configured — tier-2 categories will fail",
                    aiProperties.getInsights().getClassifier().getMode());
            return new CategoryClassifier() {
                @Override
                public String name() {
                    return "prototype:none";
                }

                @Override
                public CategoryPrediction classify(String fileName, String text) {
                    throw new IllegalStateException("no embedding model for the prototype category classifier");
                }
            };
        }
        return new PrototypeCategoryClassifier(embeddingModel, modelName, aiProperties.getInsights().getCategories(),
                aiProperties.getInsights().getClassifier());
    }
}
