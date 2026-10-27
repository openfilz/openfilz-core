package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.service.IndexedMetadataKeys;
import org.openfilz.dms.service.OpenSearchQueryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class DefaultOpenSearchQueryService implements OpenSearchQueryService {

    public static final String[] OTHER_EXCLUSIONS = {"metadata", "content"};

    /** Absent when full-text search is off (this bean exists regardless; it is then never asked). */
    private final ObjectProvider<IndexedMetadataKeys> indexedMetadataKeys;

    @Override
    public String[] getSourceOtherExclusions() {
        return OTHER_EXCLUSIONS;
    }

    @Override
    public Mono<Map<String, List<String>>> metadataKeySpellings(Collection<String> keys) {
        IndexedMetadataKeys index = indexedMetadataKeys.getIfAvailable();
        return index == null ? Mono.just(Map.of()) : index.spellings(keys);
    }
}
