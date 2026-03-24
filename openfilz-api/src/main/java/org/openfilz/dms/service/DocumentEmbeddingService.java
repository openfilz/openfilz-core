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
     * Remove all embeddings for a given document.
     *
     * @param documentId the document UUID
     * @return empty Mono on completion
     */
    Mono<Void> removeEmbeddings(UUID documentId);
}
