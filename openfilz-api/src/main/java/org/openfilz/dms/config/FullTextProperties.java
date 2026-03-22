package org.openfilz.dms.config;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.List;

/**
 * Configuration properties for OpenFilz full-text search.
 * Maps to openfilz.full-text.* properties in application.yml.
 */
@Data
@Lazy
@Configuration
@ConfigurationProperties(prefix = "openfilz.full-text")
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true")
public class FullTextProperties {

    /**
     * Stemmer languages used by the content analyzer for full-text search.
     * Each language adds a stemmer filter that normalizes words to their root form,
     * enabling searches like "système" to match "systèmes" (plural/singular),
     * or "running" to match "run" (verb forms).
     * <p>
     * Supported languages (OpenSearch built-in stemmers):
     * ar (Arabic), de (German), en (English), es (Spanish), fr (French),
     * it (Italian), nl (Dutch), pt (Portuguese), ru (Russian), sv (Swedish),
     * and many more — see OpenSearch documentation for the full list.
     * <p>
     * Default: fr, en (French and English).
     * Example in application.yml:
     * <pre>
     * openfilz:
     *   full-text:
     *     content-languages: fr,en,de,es
     * </pre>
     */
    private List<String> contentLanguages = List.of("fr", "en");
}
