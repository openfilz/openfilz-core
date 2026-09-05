package org.openfilz.dms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.service.DocumentEmbeddingService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@ConditionalOnProperties(value = {
        @ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "false", matchIfMissing = true),
        @ConditionalOnProperty(name = "openfilz.thumbnail.active", havingValue = "false", matchIfMissing = true)
})
public class EmptyMetadataPostProcessor implements MetadataPostProcessor {

    // @Lazy injection point: the embedding service bean is always defined now (the AI toggle
    // is runtime-only for native images) but must not be CREATED unless AI is actually active
    // — its dependency chain needs an EmbeddingModel that only exists when AI is on.
    @Autowired(required = false)
    @Lazy
    private DocumentEmbeddingService documentEmbeddingService;

    @Value("${openfilz.ai.active:false}")
    private boolean aiActive;

    @Override
    public void processDocument(Document document) {
        if (aiActive && documentEmbeddingService != null && document.getType() == DocumentType.FILE) {
            log.debug("[AI-EMBED] Triggering standalone embedding for '{}' (no full-text, no thumbnails)", document.getName());
            documentEmbeddingService.embedDocument(document).subscribe();
        }
    }

    @Override
    public void deleteDocument(UUID id) {
        // A hard delete must not leave chunks behind: the chat would keep surfacing a vanished document.
        if (aiActive && documentEmbeddingService != null) {
            documentEmbeddingService.removeEmbeddings(id).subscribe();
        }
    }
}
