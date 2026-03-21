package org.openfilz.dms.service;

import org.openfilz.dms.enums.OpenSearchDocumentKey;
import org.opensearch.client.opensearch._types.analysis.Analyzer;
import org.opensearch.client.opensearch._types.analysis.CustomAnalyzer;
import org.opensearch.client.opensearch._types.analysis.TokenFilter;
import org.opensearch.client.opensearch._types.analysis.TokenFilterDefinition;
import org.opensearch.client.opensearch._types.analysis.StemmerTokenFilter;
import org.opensearch.client.opensearch._types.mapping.DynamicMapping;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.opensearch.indices.IndexSettingsAnalysis;
import org.opensearch.client.util.ObjectBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.util.Map.entry;

public interface IndexMappingsProvider {

    /** Name of the custom analyzer applied to the content field. */
    String CONTENT_ANALYZER = "content_analyzer";

    /** Maps ISO language codes to OpenSearch stemmer language names. */
    Map<String, String> STEMMER_LANGUAGES = Map.ofEntries(
            entry("ar", "arabic"),
            entry("de", "german"),
            entry("en", "english"),
            entry("es", "spanish"),
            entry("fr", "french"),
            entry("it", "italian"),
            entry("nl", "dutch"),
            entry("pt", "portuguese"),
            entry("ru", "russian"),
            entry("sv", "swedish")
    );

    default Function<TypeMapping.Builder, ObjectBuilder<TypeMapping>> getIndexMappings() {
        return this::baseMappings;
    }

    default TypeMapping.Builder baseMappings(TypeMapping.Builder m) {
        return m
                .properties(OpenSearchDocumentKey.id.toString(), p -> p.keyword(k -> k))
                .properties(OpenSearchDocumentKey.name.toString(), p -> p.text(tx -> tx.fields("keyword", b -> b.keyword(builder -> builder))))
                .properties(OpenSearchDocumentKey.name_suggest.toString(), p -> p.searchAsYouType(builder -> builder))
                .properties(OpenSearchDocumentKey.extension.toString(), p -> p.keyword(k -> k))
                .properties(OpenSearchDocumentKey.size.toString(), p -> p.long_(k -> k))
                .properties(OpenSearchDocumentKey.parentId.toString(), p -> p.keyword(k -> k))
                .properties(OpenSearchDocumentKey.createdAt.toString(), p -> p.date(k -> k))
                .properties(OpenSearchDocumentKey.updatedAt.toString(), p -> p.date(k -> k))
                .properties(OpenSearchDocumentKey.createdBy.toString(), p -> p.keyword(k -> k))
                .properties(OpenSearchDocumentKey.updatedBy.toString(), p -> p.keyword(k -> k))
                .properties(OpenSearchDocumentKey.content.toString(), p -> p.text(txt -> txt.analyzer(CONTENT_ANALYZER)))
                .properties(OpenSearchDocumentKey.metadata.toString(), p -> p.object(tx -> tx.dynamic(DynamicMapping.True)))
                .properties(OpenSearchDocumentKey.active.toString(), p -> p.boolean_(b -> b));
    }

    /**
     * Builds the index settings containing the custom content analyzer.
     * <p>
     * The analyzer chain is: standard tokenizer → lowercase → asciifolding → language stemmers.
     * <ul>
     *   <li><b>asciifolding</b>: normalizes accented characters (e.g. "systèmes" → "systemes"),
     *       so searching "systemes" matches content with "systèmes".</li>
     *   <li><b>stemmer</b> filters: reduce words to their root form (e.g. "systèmes" → "system",
     *       "contracts" → "contract"), enabling singular/plural and conjugation matching.</li>
     * </ul>
     * The languages are configurable via {@code openfilz.full-text.content-languages} in application.yml.
     * Supported values are OpenSearch built-in stemmer languages: fr, en, de, es, it, nl, pt, ar, ru, sv, etc.
     *
     * @param languages list of language codes for stemmer filters (e.g. ["fr", "en"])
     * @return index settings with the custom analyzer configured
     */
    default IndexSettings getIndexSettings(List<String> languages) {
        // Build the list of token filter names for the analyzer
        List<String> filterNames = new ArrayList<>();
        filterNames.add("lowercase");
        filterNames.add("asciifolding");

        // Build stemmer filter definitions: one per language
        // OpenSearch expects full language names (e.g. "french", "english"), not ISO codes
        Map<String, TokenFilter> filterDefinitions = new HashMap<>();
        for (String lang : languages) {
            String code = lang.trim().toLowerCase();
            String stemmerName = STEMMER_LANGUAGES.getOrDefault(code, code);
            String filterName = "stemmer_" + code;
            filterNames.add(filterName);
            filterDefinitions.put(filterName, new TokenFilter.Builder()
                    .definition(new TokenFilterDefinition.Builder()
                            .stemmer(new StemmerTokenFilter.Builder().language(stemmerName).build())
                            .build())
                    .build());
        }

        // Build the custom analyzer
        Analyzer contentAnalyzer = new Analyzer.Builder()
                .custom(new CustomAnalyzer.Builder()
                        .tokenizer("standard")
                        .filter(filterNames)
                        .build())
                .build();

        // Build the analysis settings
        IndexSettingsAnalysis.Builder analysisBuilder = new IndexSettingsAnalysis.Builder()
                .analyzer(CONTENT_ANALYZER, contentAnalyzer);
        filterDefinitions.forEach(analysisBuilder::filter);

        return new IndexSettings.Builder()
                .analysis(analysisBuilder.build())
                .build();
    }
}
