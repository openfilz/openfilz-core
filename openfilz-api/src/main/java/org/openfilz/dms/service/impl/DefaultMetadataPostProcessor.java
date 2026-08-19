package org.openfilz.dms.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.MetadataPostProcessingCondition;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.service.DocumentEmbeddingService;
import org.openfilz.dms.service.FullTextService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@Lazy
@Conditional(MetadataPostProcessingCondition.class)
public class DefaultMetadataPostProcessor implements MetadataPostProcessor {

    @Autowired(required = false)
    protected ThumbnailPostProcessor thumbnailPostProcessor;

    @Autowired(required = false)
    protected FullTextService fullTextService;

    @Autowired(required = false)
    protected DocumentEmbeddingService documentEmbeddingService;

    @Value("${openfilz.thumbnail.active:false}")
    private boolean thumbnailsProperty;

    @Value("${openfilz.full-text.active:false}")
    private boolean fullTextProperty;

    @Value("${openfilz.ai.active:false}")
    private boolean aiActiveProperty;

    private boolean thumbnails;
    private boolean fullText;
    private boolean aiActive;

    @PostConstruct
    private void init() {
        thumbnails = thumbnailsProperty && thumbnailPostProcessor != null;
        fullText = fullTextProperty && fullTextService != null;
        aiActive = aiActiveProperty && documentEmbeddingService != null;
        log.info("MetadataPostProcessor: fullText={}, thumbnails={}, aiEmbedding={}", fullText, thumbnails, aiActive);
    }

    @Override
    public void processDocument(Document document) {
        if(fullText) {
            fullTextService.indexDocument(document);
        }
        if(thumbnails) {
            thumbnailPostProcessor.processDocument(document);
        }
        // AI embedding: only when full-text is NOT active (otherwise, embedding
        // is triggered from LocalFullTextServiceImpl after Tika extraction to share the work)
        if(aiActive && !fullText && document.getType() == DocumentType.FILE) {
            log.debug("[AI-EMBED] Triggering standalone embedding for '{}' (no full-text active)", document.getName());
            documentEmbeddingService.embedDocument(document).subscribe();
        }
    }

    @Override
    public void processMetadata(Document document) {
        if(fullText) {
            fullTextService.indexDocumentMetadata(document);
        }
    }

    @Override
    public void copyIndex(UUID sourceFileId, Document createdDocument) {
        if(fullText) {
            fullTextService.copyIndex(sourceFileId, createdDocument);
        }
        if(thumbnails) {
            thumbnailPostProcessor.copyDocument(sourceFileId, createdDocument.getId());
        }
    }

    @Override
    public void updateIndexField(Document document, String openSearchDocumentKey, Object value) {
        if(fullText) {
            fullTextService.updateIndexField(document, openSearchDocumentKey, value);
        }
    }

    @Override
    public void updateIndexField(UUID documentId, String openSearchDocumentKey, Object value) {
        if(fullText) {
            fullTextService.updateIndexField(documentId, openSearchDocumentKey, value);
        }
    }

    @Override
    public void deleteDocument(UUID id) {
        if(fullText) {
            fullTextService.deleteDocument(id);
        }
        if(thumbnails) {
            thumbnailPostProcessor.deleteDocument(id);
        }
    }
}
