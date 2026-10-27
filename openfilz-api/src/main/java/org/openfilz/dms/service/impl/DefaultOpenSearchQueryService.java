package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.OpenSearchQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class DefaultOpenSearchQueryService implements OpenSearchQueryService {

    public static final String[] OTHER_EXCLUSIONS = {"metadata", "content"};

    private final DocumentDAO documentDAO;

    @Override
    public String[] getSourceOtherExclusions() {
        return OTHER_EXCLUSIONS;
    }

    @Override
    public Mono<List<String>> metadataKeySpellings(String key) {
        return documentDAO.findMetadataKeySpellings(key).collectList()
                .filter(spellings -> !spellings.isEmpty());
    }
}
