package org.openfilz.dms.service.insight;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.entity.AiDocumentInsight;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The library teaches its own classifier: the category of a document is the weighted vote of
 * its nearest labelled documents — the vector store's closest chunks, resolved to documents,
 * read with their stored tier-2 category — so every label the model or the user ever wrote is
 * an example, {@code other} included. No prototype text, no model, one vector query plus one
 * read; on a real library this reached 84–88 % where descriptions reached 47 % and a small
 * local model 53 % (docs/ai.md §3c).
 * <p>
 * Which labels teach is {@code learn-from}: the model's and the user's by default — the
 * classifier's own verdicts and the cold-start descriptions' are excluded so a wrong guess does
 * not breed. With fewer than {@code min-neighbours} labelled neighbours (a young library, an
 * unusual document) or a vote below {@code min-confidence}, the cold-start classifier answers.
 */
@Slf4j
public class LearnedCategoryClassifier implements CategoryClassifier {

    public static final String NAME = "learned:knn";
    public static final String USER_SOURCE = "user";

    /** Who wrote a label, read from the row's {@code model} column. */
    public enum Source {
        MODEL, USER, PROTOTYPE, LEARNED;

        public static Source of(String model) {
            if (model == null || model.isBlank()) return MODEL;
            String lower = model.trim().toLowerCase(Locale.ROOT);
            if (lower.equals(USER_SOURCE)) return USER;
            if (lower.startsWith("prototype:")) return PROTOTYPE;
            if (lower.startsWith("learned:")) return LEARNED;
            return MODEL;
        }
    }

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final DocumentInsightStore insightStore;
    private final CategoryClassifier coldStart;
    private final AiProperties.Insights.Classifier.Learned config;
    private final int maxChars;

    public LearnedCategoryClassifier(ObjectProvider<VectorStore> vectorStoreProvider, DocumentInsightStore insightStore,
                                     CategoryClassifier coldStart, AiProperties.Insights.Classifier config) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.insightStore = insightStore;
        this.coldStart = coldStart;
        this.config = config.getLearned();
        this.maxChars = Math.max(200, config.getMaxChars());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CategoryPrediction classify(UUID documentId, String fileName, String text) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            return coldStart.classify(documentId, fileName, text);
        }
        int k = Math.max(1, config.getK());
        String body = text == null ? "" : text.length() > maxChars ? text.substring(0, maxChars) : text;
        String query = (fileName == null || fileName.isBlank() ? "" : "File name: " + fileName + "\n") + body;
        List<org.springframework.ai.document.Document> hits;
        try {
            hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(k * 4)   // several chunks per document; the best per document counts
                    .similarityThreshold(Math.max(0, config.getMinSimilarity()))
                    .build());
        } catch (Exception e) {
            log.warn("[INSIGHTS] learned classifier: similarity search failed ({}) — cold start", e.getMessage());
            return coldStart.classify(documentId, fileName, text);
        }
        Map<UUID, Double> bestByDocument = new LinkedHashMap<>();
        for (org.springframework.ai.document.Document hit : hits) {
            Object raw = hit.getMetadata() == null ? null : hit.getMetadata().get("document_id");
            UUID id = raw == null ? null : parseUuid(raw.toString());
            if (id == null || id.equals(documentId)) continue;   // the document never teaches itself
            double score = hit.getScore() == null ? 0 : hit.getScore();
            bestByDocument.merge(id, score, Math::max);
            if (bestByDocument.size() >= k) break;
        }
        if (bestByDocument.isEmpty()) {
            return coldStart.classify(documentId, fileName, text);
        }
        List<AiDocumentInsight> rows = insightStore.findAll(bestByDocument.keySet()).collectList().block();
        Map<String, Double> weights = new LinkedHashMap<>();
        int voters = 0;
        Set<Source> learnFrom = config.getLearnFrom() == null || config.getLearnFrom().isEmpty()
                ? Set.of(Source.MODEL, Source.USER) : Set.copyOf(config.getLearnFrom());
        if (rows != null) {
            for (AiDocumentInsight row : rows) {
                if (row.getCategory() == null || row.getCategory().isBlank()) continue;
                if (!AiDocumentInsight.STATUS_DONE.equals(row.getStatus())) continue;
                if (!learnFrom.contains(Source.of(row.getModel()))) continue;
                Double similarity = bestByDocument.get(row.getDocumentId());
                if (similarity == null) continue;
                weights.merge(row.getCategory().trim().toLowerCase(Locale.ROOT), similarity, Double::sum);
                voters++;
            }
        }
        if (voters < Math.max(1, config.getMinNeighbours())) {
            log.debug("[INSIGHTS] learned classifier: {} labelled neighbour(s) for '{}', fewer than {} — cold start",
                    voters, fileName, config.getMinNeighbours());
            return coldStart.classify(documentId, fileName, text);
        }
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        List<CategoryPrediction.Scored> ranked = new ArrayList<>();
        weights.forEach((category, weight) -> ranked.add(new CategoryPrediction.Scored(category, total == 0 ? 0 : weight / total)));
        ranked.sort(Comparator.comparingDouble(CategoryPrediction.Scored::score).reversed());
        CategoryPrediction.Scored top = ranked.getFirst();
        if (top.score() < config.getMinConfidence()) {
            log.debug("[INSIGHTS] learned classifier: '{}' split between {} — cold start", fileName, ranked);
            return coldStart.classify(documentId, fileName, text);
        }
        return new CategoryPrediction(top.category(), top.score(), ranked);
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
