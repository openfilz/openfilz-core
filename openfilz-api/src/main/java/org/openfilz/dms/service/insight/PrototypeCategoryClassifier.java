package org.openfilz.dms.service.insight;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.service.insight.CategoryTaxonomy.Category;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Categories by prototype: one short multilingual description per category is embedded once,
 * the document's head is embedded with the same model, and the nearest description wins. No
 * generation, no chat model, one embedding call per document — a few tens of milliseconds on a
 * CPU with the embedding model the deployment already runs for search.
 * <p>
 * The confidence is the softmax of the cosine similarities at {@code temperature}: the closer the
 * runner-up, the lower it. Below {@code min-similarity} nothing fits and the answer is
 * {@value InsightResult#OTHER}. Coarse kinds (invoice / report / contract) separate well; fine
 * ones (supplier vs customer invoice) do not — that is the neighbour vote's job.
 * <p>
 * The descriptions come from the {@link CategoryTaxonomy}. The prototype vectors are cached
 * against a fingerprint of the taxonomy's content: a taxonomy an extension manages may change at
 * runtime, and the classifier must then name the new kinds and drop the old ones without a
 * restart — the next classification re-embeds, every other one costs a string comparison.
 */
@Slf4j
public class PrototypeCategoryClassifier implements CategoryClassifier {

    /** The built-in descriptions — now owned by the taxonomy; kept under the old name for the readers of this class. */
    public static final Map<String, String> DEFAULT_PROTOTYPES = CategoryTaxonomy.BUILT_IN_DESCRIPTIONS;

    private final EmbeddingModel embeddingModel;
    private final String name;
    private final CategoryTaxonomy taxonomy;
    private final double temperature;
    private final double minSimilarity;
    private final int maxChars;
    private final String prefix;
    private volatile Prototypes prototypes;

    /** The embedded descriptions of one version of the taxonomy. */
    private record Prototypes(String fingerprint, List<String> categories, float[][] vectors) {
    }

    /**
     * @param embeddingModel the deployment's embedding model (the one the vector store uses)
     * @param modelName      the embedding model's id, for {@link #name()}
     * @param taxonomy       the kinds and their descriptions; {@value InsightResult#OTHER} gets no prototype
     * @param config         mode-independent settings: temperature, floor, text head, prefix
     */
    public PrototypeCategoryClassifier(EmbeddingModel embeddingModel, String modelName, CategoryTaxonomy taxonomy,
                                       AiProperties.Insights.Classifier config) {
        this.embeddingModel = embeddingModel;
        this.name = "prototype:" + (modelName == null || modelName.isBlank() ? "embedding" : modelName);
        this.taxonomy = taxonomy;
        this.temperature = config.getTemperature() > 0 ? config.getTemperature() : 0.02;
        this.minSimilarity = config.getMinSimilarity();
        this.maxChars = Math.max(200, config.getMaxChars());
        this.prefix = config.getPrefix() == null ? "" : config.getPrefix();
    }

    /**
     * A classifier over a fixed key list (tests, benchmarks): the built-in descriptions, overridden
     * per key by {@code config.prototypes}.
     */
    public PrototypeCategoryClassifier(EmbeddingModel embeddingModel, String modelName, List<String> categories,
                                       AiProperties.Insights.Classifier config) {
        this(embeddingModel, modelName, PropertiesCategoryTaxonomy.of(categories, config.getPrototypes()), config);
    }

    /** The categories that carry a prototype with their description, in order: every listed one but {@value InsightResult#OTHER}. */
    static Map<String, String> prototypes(List<String> categories, Map<String, String> overrides) {
        return prototypes(PropertiesCategoryTaxonomy.build(categories, overrides));
    }

    /** What is embedded per category: the description (else the key as words) and the examples, {@value InsightResult#OTHER} excluded. */
    static Map<String, String> prototypes(List<Category> categories) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Category category : categories) {
            if (InsightResult.OTHER.equals(category.key())) continue;
            // A category nobody described is still a word the model can place
            String text = category.description().isEmpty() ? category.key().replace('-', ' ') : category.description();
            if (!category.examples().isEmpty()) {
                text += " Examples: " + String.join(", ", category.examples()) + ".";
            }
            out.put(category.key(), text);
        }
        return out;
    }

    @Override
    public String name() {
        return name;
    }

    /** The categories that carry a prototype, in the taxonomy's current order. */
    public List<String> categories() {
        return List.copyOf(prototypes(taxonomy.categories()).keySet());
    }

    @Override
    public CategoryPrediction classify(UUID documentId, String fileName, String text) {
        return decide(similarities(fileName, text), temperature, minSimilarity);
    }

    /**
     * Every prototype category with its cosine similarity to the document, best first. One
     * embedding call; the benchmark reuses it to score several temperatures at once.
     */
    public List<CategoryPrediction.Scored> similarities(String fileName, String text) {
        Prototypes current = prototypes();
        if (current.categories().isEmpty()) {
            return List.of();
        }
        float[] vector = embeddingModel.embed(input(fileName, text));
        List<CategoryPrediction.Scored> scored = new ArrayList<>(current.categories().size());
        for (int i = 0; i < current.categories().size(); i++) {
            scored.add(new CategoryPrediction.Scored(current.categories().get(i), cosine(vector, current.vectors()[i])));
        }
        scored.sort(Comparator.comparingDouble(CategoryPrediction.Scored::score).reversed());
        return scored;
    }

    /**
     * The verdict for sorted similarities: the best category, its softmax share at
     * {@code temperature} as the confidence, {@value InsightResult#OTHER} below {@code minSimilarity}.
     */
    public static CategoryPrediction decide(List<CategoryPrediction.Scored> sorted, double temperature, double minSimilarity) {
        if (sorted == null || sorted.isEmpty()) {
            return new CategoryPrediction(InsightResult.OTHER, 0, List.of());
        }
        double t = temperature > 0 ? temperature : 0.02;
        double best = sorted.getFirst().score();
        double sum = 0;
        for (CategoryPrediction.Scored s : sorted) {
            sum += Math.exp((s.score() - best) / t);
        }
        double confidence = 1 / sum;
        String category = best >= minSimilarity ? sorted.getFirst().category() : InsightResult.OTHER;
        return new CategoryPrediction(category, confidence, sorted);
    }

    String input(String fileName, String text) {
        String body = text == null ? "" : text.length() > maxChars ? text.substring(0, maxChars) : text;
        String name = fileName == null ? "" : fileName;
        return prefix + (name.isEmpty() ? "" : "File name: " + name + "\n") + body;
    }

    /**
     * The prototypes of the taxonomy as it is now: reused while its fingerprint is unchanged,
     * re-embedded in one batch when a kind or a description changed.
     */
    private Prototypes prototypes() {
        Map<String, String> wanted = prototypes(taxonomy.categories());
        String fingerprint = fingerprint(wanted);
        Prototypes current = prototypes;
        if (current != null && current.fingerprint().equals(fingerprint)) {
            return current;
        }
        synchronized (this) {
            current = prototypes;
            if (current != null && current.fingerprint().equals(fingerprint)) {
                return current;
            }
            List<String> categories = List.copyOf(wanted.keySet());
            float[][] vectors = new float[0][];
            if (!categories.isEmpty()) {
                List<String> inputs = wanted.values().stream().map(d -> prefix + d).toList();
                List<float[]> embedded = embeddingModel.embed(inputs);
                if (embedded.size() != inputs.size()) {
                    throw new IllegalStateException("the embedding model returned " + embedded.size()
                            + " vectors for " + inputs.size() + " prototypes");
                }
                vectors = embedded.toArray(new float[0][]);
            }
            log.info("[INSIGHTS] {} prototype(s) embedded for the category classifier ({}){}", categories.size(), name,
                    prototypes == null ? "" : " — the taxonomy changed");
            current = new Prototypes(fingerprint, categories, vectors);
            prototypes = current;
            return current;
        }
    }

    /** A cheap identity of the taxonomy's content: the keys and the texts embedded for them, in order. */
    static String fingerprint(Map<String, String> prototypes) {
        StringBuilder sb = new StringBuilder();
        prototypes.forEach((key, text) -> sb.append(key).append('').append(text).append(''));
        return Integer.toHexString(sb.toString().hashCode()) + ':' + sb.length();
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }
}
