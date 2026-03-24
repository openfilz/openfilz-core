package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.service.DocumentEmbeddingService;
import org.openfilz.dms.service.StorageService;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implementation of DocumentEmbeddingService using Spring AI's VectorStore and Tika.
 * Extracts text from documents, splits into chunks, and stores embeddings in pgvector.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.ai.active", havingValue = "true")
public class DocumentEmbeddingServiceImpl implements DocumentEmbeddingService {

    private final VectorStore vectorStore;
    private final StorageService storageService;
    private final AiProperties aiProperties;

    @Override
    public Mono<Void> embedDocument(Document document) {
        if (document.getType() != DocumentType.FILE) {
            return Mono.empty();
        }

        return storageService.loadFile(document.getStoragePath())
                .flatMap(resource -> extractAndEmbed(document, (Resource) resource))
                .doOnSuccess(v -> log.info("Successfully embedded document: {} ({})", document.getName(), document.getId()))
                .doOnError(e -> log.error("Failed to embed document: {} ({})", document.getName(), document.getId(), e))
                .onErrorResume(e -> Mono.empty()); // Don't fail the upload if embedding fails
    }

    @Override
    public Mono<Void> removeEmbeddings(UUID documentId) {
        return Mono.fromRunnable(() -> {
            try {
                vectorStore.delete(
                        List.of("document_id:" + documentId.toString())
                );
                log.info("Removed embeddings for document: {}", documentId);
            } catch (Exception e) {
                log.warn("Failed to remove embeddings for document: {}", documentId, e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Mono<Void> extractAndEmbed(Document document, Resource resource) {
        return Mono.fromCallable(() -> {
            // Extract text using Tika
            var tikaReader = new TikaDocumentReader(resource);
            List<org.springframework.ai.document.Document> tikaDocuments = tikaReader.get();

            // Split into chunks
            var splitter = new TokenTextSplitter(
                    aiProperties.getEmbedding().getChunkSize(),
                    aiProperties.getEmbedding().getChunkOverlap(),
                    5,    // minChunkSizeChars
                    10000, // maxNumChunks
                    true   // keepSeparator
            );
            List<org.springframework.ai.document.Document> chunks = splitter.apply(tikaDocuments);

            // Enrich metadata so we can filter/retrieve by document ID later
            for (var chunk : chunks) {
                chunk.getMetadata().putAll(Map.of(
                        "document_id", document.getId().toString(),
                        "document_name", document.getName(),
                        "content_type", document.getContentType() != null ? document.getContentType() : "",
                        "parent_id", document.getParentId() != null ? document.getParentId().toString() : ""
                ));
            }

            // Store embeddings in pgvector
            if (!chunks.isEmpty()) {
                vectorStore.add(chunks);
                log.debug("Stored {} chunks for document: {}", chunks.size(), document.getName());
            }

            return chunks.size();
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
