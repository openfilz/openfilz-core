package org.openfilz.dms.service.insight;

import org.openfilz.dms.config.AiProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The core taxonomy: the keys of {@code openfilz.ai.insights.categories} in their configured
 * order, described by the built-in descriptions ({@link CategoryTaxonomy#BUILT_IN_DESCRIPTIONS})
 * unless {@code openfilz.ai.insights.classifier.prototypes} overrides one, named by the built-in
 * labels ({@link CategoryLabels}) overlaid with {@code openfilz.ai.insights.category-labels}; no examples.
 * {@value InsightResult#OTHER} is always present, always last, wherever the property puts it.
 * A key nobody described is still a word a model can place, so it gets an empty description.
 * <p>
 * Rebuilt on every call: the list is a dozen records and the properties are the only source, so
 * there is nothing worth caching — and no cache to invalidate when an extension swaps the source.
 */
@Service
public class PropertiesCategoryTaxonomy implements CategoryTaxonomy {

    private final AiProperties aiProperties;

    public PropertiesCategoryTaxonomy(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    @Override
    public List<Category> categories() {
        AiProperties.Insights insights = aiProperties.getInsights();
        return build(insights.getCategories(), insights.getClassifier().getPrototypes(), insights.getCategoryLabels());
    }

    /**
     * A fixed taxonomy from a key list, for tests and benchmarks that have no properties bean.
     *
     * @param overrides description per key on top of the built-in ones; may be null
     */
    public static CategoryTaxonomy of(List<String> keys, Map<String, String> overrides) {
        List<Category> categories = build(keys, overrides);
        return () -> categories;
    }

    /** The categories for a key list: normalised, de-duplicated, described, {@value InsightResult#OTHER} last. */
    public static List<Category> build(List<String> keys, Map<String, String> overrides) {
        return build(keys, overrides, null);
    }

    /**
     * {@link #build(List, Map)} with label overrides.
     *
     * @param labelOverrides {@code key → language → label} on top of the built-in labels; may be null
     */
    public static List<Category> build(List<String> keys, Map<String, String> overrides, Map<String, Map<String, String>> labelOverrides) {
        Map<String, String> described = new LinkedHashMap<>();
        List<String> listed = keys == null || keys.isEmpty() ? defaultKeys() : keys;
        for (String raw : listed) {
            String key = CategoryTaxonomy.normalise(raw);
            if (key.isEmpty() || InsightResult.OTHER.equals(key) || described.containsKey(key)) {
                continue;
            }
            described.put(key, describe(key, overrides));
        }
        Map<String, Map<String, String>> labels = normaliseLabelKeys(labelOverrides);
        List<Category> out = new ArrayList<>(described.size() + 1);
        described.forEach((key, description) -> out.add(new Category(key, description, List.of(), label(key, labels))));
        out.add(new Category(InsightResult.OTHER, describe(InsightResult.OTHER, overrides), List.of(), label(InsightResult.OTHER, labels)));
        return List.copyOf(out);
    }

    private static String describe(String key, Map<String, String> overrides) {
        String override = overrides == null ? null : overrides.get(key);
        if (override != null && !override.isBlank()) {
            return override;
        }
        return BUILT_IN_DESCRIPTIONS.getOrDefault(key, "");
    }

    private static Map<String, String> label(String key, Map<String, Map<String, String>> overrides) {
        return CategoryLabels.merge(CategoryLabels.builtIn(key), overrides.get(key));
    }

    /** The label overrides keyed like the categories ({@code ID_Document} and {@code id-document} are one kind). */
    private static Map<String, Map<String, String>> normaliseLabelKeys(Map<String, Map<String, String>> overrides) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (overrides != null) {
            overrides.forEach((key, labels) -> out.merge(CategoryTaxonomy.normalise(key), labels == null ? Map.of() : labels,
                    CategoryLabels::merge));
        }
        return out;
    }

    /** The built-in list, when a deployment empties the property: what {@code AiProperties.Insights} defaults to. */
    private static List<String> defaultKeys() {
        return new AiProperties.Insights().getCategories();
    }
}
