package org.openfilz.dms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Suggestions of metadata keys for the metadata search filter
 * ({@code GET /api/v1/documents/metadata-keys}, {@code openfilz.metadata.key-suggestions.*}).
 * <p>
 * The key listing reads every active document carrying metadata (no index can enumerate JSON
 * keys), so it is bounded three ways instead: a short cache, a cap on the number of keys and a
 * statement timeout after which the endpoint answers an empty list.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "openfilz.metadata.key-suggestions")
public class MetadataKeySuggestionProperties {

    /** Read per request: off = the endpoint answers 404 and the web app keeps a plain key input. */
    private boolean enabled = true;

    /** Most frequent keys returned, at most. */
    private int maxKeys = 200;

    /** How long a key list is served from the cache (per user when documents are per user). */
    private Duration cacheTtl = Duration.ofMinutes(2);

    /** Cached key lists, at most. */
    private long cacheMaxEntries = 10_000;

    /** Statement timeout of the key listing; past it the endpoint answers an empty list. */
    private Duration statementTimeout = Duration.ofSeconds(3);

    /** Keys written by the platform itself, never suggested. */
    private List<String> excludedKeys = new ArrayList<>(List.of("sha256"));
}
