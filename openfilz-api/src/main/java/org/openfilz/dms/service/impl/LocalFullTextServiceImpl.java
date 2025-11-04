package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.FullTextService;
import org.openfilz.dms.service.IndexService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperties({
        @ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true"),
        @ConditionalOnProperty(name = "openfilz.full-text.indexation-mode", havingValue = "local", matchIfMissing = true)
})
public class LocalFullTextServiceImpl implements FullTextService {

    // Taille du buffer entre DataBuffer -> PipedInputStream
    private static final int PIPE_BUFFER_SIZE = 64 * 1024; // 64 KiB

    // Limite de débit (en DataBuffers) pour éviter les débordements
    private static final int RATE_LIMIT = 256;

    private final IndexService indexService;
    private final ReactiveOpenSearchIndexer indexer;
    private final TikaService tikaService;

    @Override
    public void indexDocument(FilePart filePart, Document document) {
        indexer.indexMetadata(document.getId(), indexService.newOpenSearchDocumentMetadata(document))
                .then(tikaService.extractTextAsFlux(filePart.content())
                        .as(flux -> indexer.indexDocumentStream(flux, document.getId()))
                        .then(indexer.updateMetadata(document.getId(), Map.of("doc-status", "ready")))
                )
                .subscribe();
    }

    @Override
    public void indexDocumentMetadata(Document document) {
        Mono.fromCallable(() -> indexService.updateMetadata(document))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(err ->
                        log.error("indexDocumentMetadata error for {} : {}", document.getId(), err.getMessage()))
                .subscribe();
    }

    @Override
    public void copyIndex(UUID sourceFileId, Document createdDocument) {
        indexService.copyIndex(sourceFileId, createdDocument)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(err ->
                        log.error("copyIndex error for {} : {}", createdDocument.getId(), err.getMessage()))
                .subscribe();
    }

    @Override
    public void updateIndexField(Document document, String openSearchDocumentKey, Object value) {
        Mono.fromCallable(() -> indexService.updateIndexField(document, openSearchDocumentKey, value))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(err ->
                        log.error("updateIndexField error for {} : {}", document, err.getMessage()))
                .subscribe();
    }

    @Override
    public void deleteDocument(UUID id) {
        Mono.fromCallable(() -> indexService.deleteDocument(id))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(err ->
                        log.error("deleteDocument error for {} : {}", id, err.getMessage()))
                .subscribe();
    }


}
