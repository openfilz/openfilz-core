package org.openfilz.dms.service;

import org.openfilz.dms.entity.Document;

import java.util.UUID;

public interface MetadataPostProcessor {

    void processDocument(Document document);

    void processMetadata(Document document);

    void copyIndex(UUID sourceFileId, Document createdDocument);

    void updateIndexField(Document document, String openSearchDocumentKey, Object value);

    void deleteDocument(UUID id);
}
