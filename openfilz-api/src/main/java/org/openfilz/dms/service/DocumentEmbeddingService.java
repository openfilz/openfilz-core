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
     * Remove all embeddings for a given document.
     *
     * @param documentId the document UUID
     * @return empty Mono on completion
     */
    Mono<Void> removeEmbeddings(UUID documentId);
}
