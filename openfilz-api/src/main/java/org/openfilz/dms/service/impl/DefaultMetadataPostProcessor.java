package org.openfilz.dms.service.impl;

import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "false", matchIfMissing = true)
public class DefaultMetadataPostProcessor implements MetadataPostProcessor {
    @Override
    public void process(Document document) {}
}
