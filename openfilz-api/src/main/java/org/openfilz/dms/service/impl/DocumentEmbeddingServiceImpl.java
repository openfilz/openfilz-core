package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.service.DocumentEmbeddingService;
import org.openfilz.dms.service.StorageService;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implementation of DocumentEmbeddingService using Spring AI's VectorStore and TikaService.
 * <p>
 * Two entry points:
 * <ul>
 *   <li>{@link #embedDocument(Document)} — standalone extraction using TikaService (memory-safe,
 *       spools to temp file). Used when full-text search is NOT active.</li>
 *   <li>{@link #embedFromText(Document, String)} — receives pre-extracted text from
 *       full-text indexing (shared Tika extraction). Used when full-text IS active.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.ai.active", havingValue = "true")
public class DocumentEmbeddingServiceImpl implements DocumentEmbeddingService {

    private final VectorStore vectorStore;
    private final StorageService storageService;
    private final AiProperties aiProperties;
    private final TikaService tikaService;

    @Override
    public Mono<Void> embedDocument(Document document) {
        if (document.getType() != DocumentType.FILE) {
            log.debug("[AI-EMBED] Skipping folder: {} ({})", document.getName(), document.getId());
            return Mono.empty();
        }

        log.info("[AI-EMBED] Starting standalone embedding for: '{}' (id={}, type={})",
                document.getName(), document.getId(), document.getContentType());

        // Use TikaService for memory-safe extraction (spools to temp file, streams text)
        // then collect the streamed text and pass to embedFromText()
        try {
            Path tempFile = Files.createTempFile("ai-embed-", ".tmp");

            return tikaService.processResource(tempFile, storageService.loadFile(document.getStoragePath()))
                    .reduce(new StringBuilder(), StringBuilder::append)
                    .flatMap(collectedText -> {
                        // Clean up temp file
                        try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}

                        String text = collectedText.toString();
                        if (text.isBlank()) {
                            log.warn("[AI-EMBED] No text extracted for '{}' — file may be binary", document.getName());
                            return Mono.empty();
                        }

                        log.debug("[AI-EMBED] Tika extracted {} chars for '{}'", text.length(), document.getName());
                        return embedFromText(document, text);
                    })
                    .doOnError(e -> {
                        log.error("[AI-EMBED] Embedding FAILED for '{}': {}", document.getName(), e.getMessage());
                        try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
                    })
                    .onErrorResume(e -> Mono.empty())
                    .then();
        } catch (IOException e) {
            log.error("[AI-EMBED] Failed to create temp file for '{}': {}", document.getName(), e.getMessage());
            return Mono.empty();
        }
    }

    @Override
    public Mono<Void> embedFromText(Document document, String extractedText) {
        if (document.getType() != DocumentType.FILE || extractedText == null || extractedText.isBlank()) {
            return Mono.empty();
        }

        log.info("[AI-EMBED] Embedding text for '{}' ({} chars)", document.getName(), extractedText.length());

        return Mono.fromCallable(() -> {
            var aiDoc = new org.springframework.ai.document.Document(extractedText);

            var splitter = new TokenTextSplitter(
                    aiProperties.getEmbedding().getChunkSize(),
                    aiProperties.getEmbedding().getChunkOverlap(),
                    5, 10000, true,
                    List.of('.', '!', '?', '\n')
            );
            List<org.springframework.ai.document.Document> chunks = splitter.apply(List.of(aiDoc));
            log.debug("[AI-EMBED] Split into {} chunks (chunkSize={}, overlap={})",
                    chunks.size(), aiProperties.getEmbedding().getChunkSize(), aiProperties.getEmbedding().getChunkOverlap());

            for (var chunk : chunks) {
                chunk.getMetadata().putAll(Map.of(
                        "document_id", document.getId().toString(),
                        "document_name", document.getName(),
                        "content_type", document.getContentType() != null ? document.getContentType() : "",
                        "parent_id", document.getParentId() != null ? document.getParentId().toString() : ""
                ));
            }

            if (!chunks.isEmpty()) {
                vectorStore.add(chunks);
                log.info("[AI-EMBED] Stored {} chunks for '{}' in vector store", chunks.size(), document.getName());
            } else {
                log.warn("[AI-EMBED] No chunks generated for '{}' — text may be too short", document.getName());
            }

            return chunks.size();
        }).subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.error("[AI-EMBED] Failed to embed '{}': {}", document.getName(), e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    @Override
    public Mono<Void> removeEmbeddings(UUID documentId) {
        log.debug("[AI-EMBED] Removing embeddings for document: {}", documentId);
        return Mono.fromRunnable(() -> {
            try {
                vectorStore.delete(List.of("document_id:" + documentId.toString()));
                log.info("[AI-EMBED] Removed embeddings for document: {}", documentId);
            } catch (Exception e) {
                log.warn("[AI-EMBED] Failed to remove embeddings for document: {}", documentId, e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
