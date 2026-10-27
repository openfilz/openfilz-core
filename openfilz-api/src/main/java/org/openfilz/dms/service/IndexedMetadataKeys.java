package org.openfilz.dms.service;

import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The metadata keys the search index knows, so that a {@code metadata.<key>} filter matches its
 * key case-insensitively where field names are not ({@code project} → {@code project},
 * {@code Project}).
 */
public interface IndexedMetadataKeys {

    /**
     * The spellings in use of each key, matched ignoring case ({@code client} →
     * {@code [client, Client]}); a key no document uses is absent from the result.
     */
    Mono<Map<String, List<String>>> spellings(Collection<String> keys);
}
