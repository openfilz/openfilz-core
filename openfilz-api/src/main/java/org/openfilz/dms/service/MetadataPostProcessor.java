package org.openfilz.dms.service;

import org.openfilz.dms.entity.Document;

import java.util.UUID;

public interface MetadataPostProcessor {

    default void processDocument(Document document) {}

    default void processMetadata(Document document) {}

    default void copyIndex(UUID sourceFileId, Document createdDocument) {}

    default void updateIndexField(Document document, String openSearchDocumentKey, Object value) {}

    default void deleteDocument(UUID id) {}

}
