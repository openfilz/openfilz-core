package org.openfilz.dms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.service.DocumentEmbeddingService;
import org.openfilz.dms.service.FullTextService;
import org.openfilz.dms.service.IndexService;
import org.openfilz.dms.service.StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@Slf4j
@Service
@Lazy
@ConditionalOnProperties({
        @ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true"),
        @ConditionalOnProperty(name = "openfilz.full-text.indexation-mode", havingValue = "local", matchIfMissing = true)
})
public class LocalFullTextServiceImpl implements FullTextService {

    /**
     * MIME type prefixes for which Tika text extraction is relevant.
     * Files with other content types (images, audio, video, fonts, etc.)
     * still get their metadata indexed but skip Tika processing.
     */
    private static final Set<String> TEXT_EXTRACTABLE_PREFIXES = Set.of(
            "text/",                                            // text/plain, text/html, text/csv, text/xml, text/markdown, ...
            "application/pdf",
            "application/msword",                               // .doc
            "application/vnd.openxmlformats-officedocument.",    // .docx, .xlsx, .pptx
            "application/vnd.ms-excel",                         // .xls
            "application/vnd.ms-powerpoint",                    // .ppt
            "application/vnd.oasis.opendocument.",              // .odt, .ods, .odp
            "application/rtf",
            "application/json",
            "application/xml",
            "application/xhtml+xml",
            "application/epub+zip",
            "application/vnd.ms-outlook",                       // .msg
            "message/rfc822"                                    // .eml
    );

    private final IndexService indexService;
    private final TikaService tikaService;
    private final StorageService storageService;

    // @Lazy injection point: the embedding service bean is always defined now (the AI toggle
    // is runtime-only for native images) but must not be CREATED unless AI is actually active
    // — its dependency chain needs an EmbeddingModel that only exists when AI is on.
    @Autowired(required = false)
    @Lazy
    private DocumentEmbeddingService documentEmbeddingService;

    @org.springframework.beans.factory.annotation.Value("${openfilz.ai.active:false}")
    private boolean aiActive;

    /** Tier-1 document insights (the file's own metadata), captured from the same Tika pass. */
    // ObjectProvider, not @Lazy: DocumentInsightStore is a concrete class with no interface, so a
    // @Lazy injection point yields a CGLIB lazy-resolution proxy that has no reflection metadata
    // in a native image (MissingReflectionRegistrationError on CGLIB$FACTORY_DATA at boot).
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<org.openfilz.dms.service.insight.DocumentInsightStore> insightStoreProvider;

    /** Tier-2 document insights (model enrichment), queued with the text this pass extracted. */
    @Autowired(required = false)
    @Lazy
    private org.openfilz.dms.service.insight.DocumentInsightService insightService;

    public LocalFullTextServiceImpl(IndexService indexService, TikaService tikaService, StorageService storageService) {
        this.indexService = indexService;
        this.tikaService = tikaService;
        this.storageService = storageService;
    }

    @Override
    public void indexDocument(Document document) {
        if(document.getType() == DocumentType.FILE) {
            indexFile(document);
        } else {
            indexFolder(document);
        }
    }

    private void indexFolder(Document document) {
        doIndexDocMetadataMono(document, "Retrying indexFolder for document {}, attempt {}", "indexFolder error for {} : {}");
    }

    private void indexFile(Document document) {
        if(document.getSize() > 0 && isTextExtractable(document.getContentType())) {
            indexFileWithTextExtraction(document);
        } else {
            doIndexDocMetadataMono(document, "Retrying indexFile for document {}, attempt {}", "indexFile error for {} : {}");
        }
    }

    private void indexFileWithTextExtraction(Document document) {
        try {
            Path tempFile = Files.createTempFile("upload-opf", ".tmp");

            // When AI embedding is active, collect the Tika-extracted text to reuse it
            // for vector embedding — avoids a second Tika pass on the same file.
            final boolean shareWithAi = aiActive && documentEmbeddingService != null;
            final StringBuilder collectedText = shareWithAi ? new StringBuilder() : null;

            Flux<String> tikaFlux = tikaService.processResource(tempFile, storageService.loadFile(document.getStoragePath()),
                    metadata -> saveFileMetadata(document, metadata));

            // If sharing with AI, tap into the stream to collect text (lightweight — just appending strings)
            if (shareWithAi) {
                tikaFlux = tikaFlux.doOnNext(collectedText::append);
            }

            subscribeAndRetryOnError(indexService.indexDocMetadataMono(document)
                    .then(tikaFlux.as(flux -> indexService.indexDocumentStream(flux, document.getId())))
                    .then(Mono.fromRunnable(() -> {
                        try {
                            Files.deleteIfExists(tempFile);
                            log.debug("Cleaned up stable temp file [{}].", tempFile);
                        } catch (Exception e) {
                            log.error("Failed to clean up stable temp file [{}].", tempFile, e);
                        }
                        // After OpenSearch indexing completes, trigger AI embedding with the collected text
                        if (shareWithAi && collectedText.length() > 0) {
                            log.debug("[AI-EMBED] Sharing Tika-extracted text with AI embedding for '{}' ({} chars)",
                                    document.getName(), collectedText.length());
                            documentEmbeddingService.embedFromText(document, collectedText.toString()).subscribe();
                            enqueueInsights(document, collectedText);
                        }
                    })),
                    document,
                    "Retrying indexFile for document {}, attempt {}", "indexFile error for {} : {}"
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Tier-2 insight: hand the text head to the enrichment queue (returns at once; off = no-op). */
    private void enqueueInsights(Document document, CharSequence text) {
        try {
            if (insightService != null && insightService.isActive()) {
                insightService.enqueue(document, text.subSequence(0, Math.min(text.length(), 12_000)).toString());
            }
        } catch (Exception e) {
            log.warn("[INSIGHTS] could not queue enrichment of {}: {}", document.getId(), e.getMessage());
        }
    }

    /** Tier-1 insight from the parse that already ran: no second Tika pass, no extra I/O. */
    private void saveFileMetadata(Document document, org.apache.tika.metadata.Metadata metadata) {
        org.openfilz.dms.service.insight.DocumentInsightStore insightStore = insightStoreProvider.getIfAvailable();
        if (insightStore == null) {
            return;
        }
        try {
            insightStore.saveFileMetadata(document, org.openfilz.dms.service.insight.TikaFileMetadata.from(metadata))
                    .subscribe(v -> { },
                            e -> log.warn("[INSIGHTS] tier-1 save failed for {}: {}", document.getId(), e.getMessage()));
        } catch (Exception e) {
            log.warn("[INSIGHTS] tier-1 save failed for {}: {}", document.getId(), e.getMessage());
        }
    }

    private static boolean isTextExtractable(String contentType) {
        if (contentType == null) {
            return false;
        }
        String ct = contentType.toLowerCase();
        for (String prefix : TEXT_EXTRACTABLE_PREFIXES) {
            if (ct.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void doIndexDocMetadataMono(Document document, String warningMessage, String errorMessage) {
        subscribeAndRetryOnError(indexService.indexDocMetadataMono(document), document, warningMessage, errorMessage);
    }

    private void subscribeAndRetryOnError(Mono<Void> indexProcessMono, Document document, String warningMessage, String errorMessage) {
        indexProcessMono.retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                        .filter(this::isRetryableException)
                        .doBeforeRetry(signal -> log.warn(warningMessage,
                                document.getId(), signal.totalRetries() + 1)))
                .doOnError(err -> log.error(errorMessage, document.getId(), err.getMessage()))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    private boolean isRetryableException(Throwable throwable) {
        // Retry on version conflict (409) and transient network errors
        String message = throwable.getMessage();
        if (message != null) {
            return message.contains("409") || message.contains("version_conflict") || message.contains("Conflict");
        }
        return false;
    }

    @Override
    public void indexDocumentMetadata(Document document) {
        Mono.fromCallable(() -> indexService.updateMetadata(document))
                .doOnError(err ->
                        log.error("indexDocumentMetadata error for {} : {}", document.getId(), err.getMessage()))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    @Override
    public void copyIndex(UUID sourceFileId, Document createdDocument) {
        indexService.copyIndex(sourceFileId, createdDocument)
                .doOnError(err ->
                        log.error("copyIndex error for {} : {}", createdDocument.getId(), err.getMessage()))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    @Override
    public void updateIndexField(Document document, String openSearchDocumentKey, Object value) {
        Mono.fromCallable(() -> indexService.updateIndexField(document, openSearchDocumentKey, value))
                .doOnError(err ->
                        log.error("updateIndexField error for {} : {}", document, err.getMessage()))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    @Override
    public void updateIndexField(UUID documentId, String openSearchDocumentKey, Object value) {
        indexService.updateIndexField(documentId, openSearchDocumentKey, value)
                .doOnError(err ->
                        log.error("updateIndexField error for {} : {}", documentId, err.getMessage()))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    @Override
    public void deleteDocument(UUID id) {
        Mono.fromCallable(() -> indexService.deleteDocument(id))
                .doOnError(err ->
                        log.error("deleteDocument error for {} : {}", id, err.getMessage()))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }


}
