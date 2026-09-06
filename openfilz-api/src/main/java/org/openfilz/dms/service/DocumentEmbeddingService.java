package org.openfilz.dms.service;

import org.openfilz.dms.entity.Document;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Service for managing document embeddings in the vector store.
 * Handles text extraction, chunking, embedding generation, and storage.
 */
public interface DocumentEmbeddingService {

    /**
     * Extract text from a document, chunk it, generate embeddings,
     * and store them in the pgvector store.
     *
     * @param document the document entity
     * @return empty Mono on completion
     */
    Mono<Void> embedDocument(Document document);

    /**
     * Embed a document from pre-extracted text content.
     * This avoids a redundant Tika extraction when full-text search already extracted the text.
     *
     * @param document the document entity
     * @param extractedText the already-extracted text content
     * @return empty Mono on completion
     */
    Mono<Void> embedFromText(Document document, String extractedText);

    /**
     * Embed an existing document again, without re-running the insights: the text from the
     * search index when full-text keeps it, else from a fresh Tika pass on the stored file.
     * Unlike the two entry points above, errors propagate — the backfill counts them.
     *
     * @param document the document entity
     * @return the number of chunks stored, 0 when the file yields no text
     */
    Mono<Integer> reembed(Document document);

    /**
     * Remove all embeddings for a given document.
     *
     * @param documentId the document UUID
     * @return empty Mono on completion
     */
    Mono<Void> removeEmbeddings(UUID documentId);
}
