package org.openfilz.dms.service.impl;

import jakarta.annotation.PostConstruct;
import org.openfilz.dms.config.MetadataPostProcessingCondition;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.FullTextService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Conditional(MetadataPostProcessingCondition.class)
public class DefaultMetadataPostProcessor implements MetadataPostProcessor {

    @Autowired(required = false)
    protected ThumbnailPostProcessor thumbnailPostProcessor;

    @Autowired(required = false)
    protected FullTextService fullTextService;

    private boolean thumbnails;
    private boolean fullText;

    @PostConstruct
    private void init() {
        if (thumbnailPostProcessor != null) {
            thumbnails = true;
        }
        if(fullTextService != null) {
            fullText = true;
        }
    }

    @Override
    public void processDocument(Document document) {
        if(fullText) {
            fullTextService.indexDocument(document);
        }
        if(thumbnails) {
            thumbnailPostProcessor.processDocument(document);
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
    public void deleteDocument(UUID id) {
        if(fullText) {
            fullTextService.deleteDocument(id);
        }
        if(thumbnails) {
            thumbnailPostProcessor.deleteDocument(id);
        }
    }
}
