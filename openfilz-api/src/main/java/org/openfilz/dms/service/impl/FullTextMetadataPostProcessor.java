package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.FullTextService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true")
public class FullTextMetadataPostProcessor implements MetadataPostProcessor {

    protected final FullTextService fullTextService;

    @Override
    public void process(Document document) {
        fullTextService.indexDocumentMetadata(document);
    }
}
