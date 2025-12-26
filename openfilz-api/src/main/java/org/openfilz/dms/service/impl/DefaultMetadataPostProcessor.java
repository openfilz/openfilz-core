package org.openfilz.dms.service.impl;

import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "false", matchIfMissing = true)
public class DefaultMetadataPostProcessor implements MetadataPostProcessor {
    @Override
    public void processDocument(Document document) {

    }

    @Override
    public void processMetadata(Document document) {}

    @Override
    public void copyIndex(UUID sourceFileId, Document createdDocument) {

    }

    @Override
    public void updateIndexField(Document document, String openSearchDocumentKey, Object value) {

    }

    @Override
    public void deleteDocument(UUID id) {

    }
}
