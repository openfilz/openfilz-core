package org.openfilz.dms.service;

import org.openfilz.dms.entity.Document;

import java.util.UUID;

public interface FullTextService {

    void indexDocument(Document document);

    void indexDocumentMetadata(Document document);

    void copyIndex(UUID sourceFileId, Document createdDocument);

    void updateIndexField(Document document, String openSearchDocumentKey, Object value);

    void updateIndexField(UUID documentId, String openSearchDocumentKey, Object value);

    void deleteDocument(UUID id);
}
