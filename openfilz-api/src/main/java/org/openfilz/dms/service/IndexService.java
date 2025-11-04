package org.openfilz.dms.service;

import org.openfilz.dms.entity.Document;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface IndexService {
    Mono<Void> indexDocument(Document document, Mono<String> text);

    Mono<Void> updateMetadata(Document document);

    Mono<Void> copyIndex(UUID sourceFileId, Document createdDocument);

    Mono<Void> updateIndexField(Document document, String openSearchDocumentKey, Object value);

    Mono<Void> deleteDocument(UUID id);
}
