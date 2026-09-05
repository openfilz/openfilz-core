package org.openfilz.dms.config;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.service.insight.CategoryClassifier;
import org.openfilz.dms.service.insight.DocumentInsightService;
import org.openfilz.dms.service.insight.DocumentInsightStore;
import org.openfilz.dms.service.insight.LearnedCategoryClassifier;
import org.openfilz.dms.service.insight.PrototypeCategoryClassifier;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import java.util.UUID;

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
     * The category classifier of the deployment, by {@code classifier.mode}: the prototype
     * descriptions on the embedding model ({@code prototype}), or the library's own labelled
     * documents with the descriptions as cold start ({@code learned}, {@code auto}). Lazy: built on
     * the first tier-2 enrichment, never in {@code llm} mode; without an embedding model it exists
     * but every classification fails with a clear message.
     */
    @Bean
    @Lazy
    public CategoryClassifier categoryClassifier(AiProperties aiProperties, Environment environment,
                                                 ObjectProvider<EmbeddingModels> embeddingModelsProvider,
                                                 ObjectProvider<VectorStore> vectorStoreProvider,
                                                 ObjectProvider<DocumentInsightStore> insightStoreProvider) {
        AiProperties.Insights.Classifier config = aiProperties.getInsights().getClassifier();
        EmbeddingModels models = embeddingModelsProvider.getIfAvailable();
        EmbeddingModel embeddingModel = models == null ? null : models.effective();
        String provider = models == null ? "" : models.provider(environment.getProperty("spring.ai.model.embedding", ""));
        String modelName = provider == null || provider.isBlank() ? ""
                : EmbeddingModels.TRANSFORMERS_PROVIDER.equals(provider)
                ? aiProperties.getTransformers().getEmbedding().getModel()
                : environment.getProperty("spring.ai." + provider + ".embedding.model", provider);
        CategoryClassifier prototype;
        if (embeddingModel == null) {
            log.warn("openfilz.ai.insights.classifier.mode is {} but no embedding model is configured — tier-2 categories will fail",
                    config.getMode());
            prototype = new CategoryClassifier() {
                @Override
                public String name() {
                    return "prototype:none";
                }

                @Override
                public CategoryPrediction classify(UUID documentId, String fileName, String text) {
                    throw new IllegalStateException("no embedding model for the prototype category classifier");
                }
            };
        } else {
            prototype = new PrototypeCategoryClassifier(embeddingModel, modelName, aiProperties.getInsights().getCategories(), config);
        }
        AiProperties.Insights.Classifier.Mode mode = config.getMode() == null ? AiProperties.Insights.Classifier.Mode.LLM : config.getMode();
        if (mode == AiProperties.Insights.Classifier.Mode.LEARNED || mode == AiProperties.Insights.Classifier.Mode.AUTO) {
            DocumentInsightStore insightStore = insightStoreProvider.getIfAvailable();
            if (insightStore != null) {
                return new LearnedCategoryClassifier(vectorStoreProvider, insightStore, prototype, config);
            }
            log.warn("openfilz.ai.insights.classifier.mode is {} but there is no insight store — the prototype descriptions classify", mode);
        }
        return prototype;
    }
}
