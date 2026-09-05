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

    Mono<Void> updateIndexField(UUID documentId, String openSearchDocumentKey, Object value);

    Mono<Void> deleteDocument(UUID id);

    Mono<Map<String, Object>> newOpenSearchDocumentMetadata(Document document);

    Mono<Void> indexMetadata(UUID documentId, Map<String, Object> metadata);

    Mono<Void> indexDocumentStream(Flux<String> textFragments, UUID documentId);

    /**
     * The indexed full text of a document, or empty when the index holds none for it (not
     * extractable, not indexed yet, or an index service without content storage). Lets the AI
     * tools reuse the text extracted at upload instead of downloading the file and parsing it again.
     */
    default Mono<String> getContent(UUID documentId) {
        return Mono.empty();
    }

    /** Set several top-level fields of a document's index entry (document-insight mirror). */
    default Mono<Void> updateIndexFields(UUID documentId, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(fields.entrySet())
                .concatMap(entry -> updateIndexField(documentId, entry.getKey(), entry.getValue()))
                .then();
    }

    default Mono<Void> indexDocMetadataMono(Document document) {
        return newOpenSearchDocumentMetadata(document)
                .flatMap(openDoc -> indexMetadata(document.getId(), openDoc));
    }
}
