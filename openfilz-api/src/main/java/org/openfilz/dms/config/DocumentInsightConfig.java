package org.openfilz.dms.config;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.service.insight.CategoryClassifier;
import org.openfilz.dms.service.insight.FileNameAwareCategoryClassifier;
import org.openfilz.dms.service.insight.CategoryTaxonomy;
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
        return active ? aiService : noOpService;
    }

    /**
     * The category classifier of the deployment. {@code classifier.mode} may change at runtime, so
     * the bean holds both classifiers and picks one per call: the prototype descriptions on the
     * embedding model ({@code prototype}, and {@code llm}, where it only screens or answers when a
     * policy bars the model), or the library's own labelled documents with the descriptions as cold
     * start ({@code learned}, {@code auto}). Building the learned one costs nothing until it is
     * asked. Lazy: built on the first tier-2 enrichment; without an embedding model it exists but
     * every classification fails with a clear message. The descriptions it embeds are the
     * {@link CategoryTaxonomy}'s: an extension's taxonomy drives them, and the classifier
     * re-embeds when the taxonomy's content changes (it caches by content fingerprint). Either way
     * it sits behind {@link FileNameAwareCategoryClassifier}: a name that says its kind wins, a file
     * with no text is not guessed from its name.
     */
    @Bean
    @Lazy
    public CategoryClassifier categoryClassifier(AiProperties aiProperties, Environment environment,
                                                 CategoryTaxonomy taxonomy,
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
            log.warn("No embedding model is configured: tier-2 categories from the local classifier will fail");
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
            prototype = new PrototypeCategoryClassifier(embeddingModel, modelName, taxonomy, config);
        }
        CategoryClassifier byDescriptions = new FileNameAwareCategoryClassifier(prototype, taxonomy);
        DocumentInsightStore insightStore = insightStoreProvider.getIfAvailable();
        CategoryClassifier learned = null;
        if (insightStore != null) {
            learned = new FileNameAwareCategoryClassifier(
                    new LearnedCategoryClassifier(vectorStoreProvider, insightStore, prototype, config), taxonomy);
        } else {
            log.warn("There is no insight store: in learned and auto modes the prototype descriptions classify");
        }
        return new ModeSwitchingCategoryClassifier(config, byDescriptions, learned);
    }

    /**
     * Picks the classifier of the mode in force at each call. Safe to call concurrently: it holds
     * no state of its own, both delegates are.
     */
    static final class ModeSwitchingCategoryClassifier implements CategoryClassifier {

        private final AiProperties.Insights.Classifier config;
        private final CategoryClassifier byDescriptions;
        private final CategoryClassifier learned;

        ModeSwitchingCategoryClassifier(AiProperties.Insights.Classifier config, CategoryClassifier byDescriptions,
                                        CategoryClassifier learned) {
            this.config = config;
            this.byDescriptions = byDescriptions;
            this.learned = learned;
        }

        CategoryClassifier current() {
            AiProperties.Insights.Classifier.Mode mode = config.getMode();
            boolean learning = mode == AiProperties.Insights.Classifier.Mode.LEARNED
                    || mode == AiProperties.Insights.Classifier.Mode.AUTO;
            return learning && learned != null ? learned : byDescriptions;
        }

        @Override
        public String name() {
            return current().name();
        }

        @Override
        public CategoryPrediction classify(UUID documentId, String fileName, String text) {
            return current().classify(documentId, fileName, text);
        }
    }
}
