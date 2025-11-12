package org.openfilz.dms.service;

import org.openfilz.dms.entity.Document;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

public interface IndexService {

    Mono<Void> updateMetadata(Document document);

    Mono<Void> copyIndex(UUID sourceFileId, Document createdDocument);

    Mono<Void> updateIndexField(Document document, String openSearchDocumentKey, Object value);

    Mono<Void> deleteDocument(UUID id);

    Map<String, Object> newOpenSearchDocumentMetadata(Document document);

    Mono<Void> indexMetadata(UUID documentId, Map<String, Object> metadata);

    Mono<Void> indexDocumentStream(Flux<String> textFragments, UUID documentId);
}
