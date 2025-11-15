package org.openfilz.dms.service.impl;

import org.openfilz.dms.service.OpenSearchQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class DefaultOpenSearchQueryService implements OpenSearchQueryService {

    public static final String[] OTHER_EXCLUSIONS = {"metadata", "content"};

    @Override
    public String[] getSourceOtherExclusions() {
        return OTHER_EXCLUSIONS;
    }
}
