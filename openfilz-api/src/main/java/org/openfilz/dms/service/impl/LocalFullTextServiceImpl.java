package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.service.FullTextService;
import org.openfilz.dms.service.IndexService;
import org.openfilz.dms.service.StorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperties({
        @ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true"),
        @ConditionalOnProperty(name = "openfilz.full-text.indexation-mode", havingValue = "local", matchIfMissing = true)
})
public class LocalFullTextServiceImpl implements FullTextService {

    private final IndexService indexService;
    private final TikaService tikaService;
    private final StorageService storageService;

    @Override
    public void indexDocument(Document document) {
        if(document.getType() == DocumentType.FILE) {
            indexFile(document);
        } else {
            indexFolder(document);
        }
    }

    private void indexFolder(Document document) {
        indexDocMetadataMono(document)
                .subscribe();
    }

    private Mono<Void> indexDocMetadataMono(Document document) {
        return indexService.indexMetadata(document.getId(), indexService.newOpenSearchDocumentMetadata(document));
    }

    private void indexFile(Document document) {
        try {
            Path tempFile = Files.createTempFile("upload-opf", ".tmp");
            indexDocMetadataMono(document)
                    .then(tikaService.processResource(tempFile, storageService.loadFile(document.getStoragePath()))
                            .as(flux -> indexService.indexDocumentStream(flux, document.getId()))
                    )
                    .then(Mono.fromRunnable(() -> {
                        try {
                            Files.deleteIfExists(tempFile);
                            log.info("Cleaned up stable temp file [{}].", tempFile);
                        } catch (Exception e) {
                            log.error("Failed to clean up stable temp file [{}].", tempFile, e);
                        }
                    }))
                    .subscribe();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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
