package org.openfilz.dms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.service.DocumentEmbeddingService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@ConditionalOnProperties(value = {
        @ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "false", matchIfMissing = true),
        @ConditionalOnProperty(name = "openfilz.thumbnail.active", havingValue = "false", matchIfMissing = true)
})
public class EmptyMetadataPostProcessor implements MetadataPostProcessor {

    @Autowired(required = false)
    private DocumentEmbeddingService documentEmbeddingService;

    @Override
    public void processDocument(Document document) {
        if (documentEmbeddingService != null && document.getType() == DocumentType.FILE) {
            log.debug("[AI-EMBED] Triggering standalone embedding for '{}' (no full-text, no thumbnails)", document.getName());
            documentEmbeddingService.embedDocument(document).subscribe();
        }
    }
}
