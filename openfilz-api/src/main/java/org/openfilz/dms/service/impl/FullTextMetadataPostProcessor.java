package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.FullTextService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true")
public class FullTextMetadataPostProcessor implements MetadataPostProcessor {

    protected final FullTextService fullTextService;

    @Override
    public void processDocument(Document document) {
        fullTextService.indexDocument(document);
    }

    @Override
    public void processMetadata(Document document) {
        fullTextService.indexDocumentMetadata(document);
    }

    @Override
    public void copyIndex(UUID sourceFileId, Document createdDocument) {
        fullTextService.copyIndex(sourceFileId, createdDocument);
    }

    @Override
    public void updateIndexField(Document document, String openSearchDocumentKey, Object value) {
        fullTextService.updateIndexField(document, openSearchDocumentKey, value);
    }

    @Override
    public void deleteDocument(UUID id) {
        fullTextService.deleteDocument(id);
    }
}
