package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.FullTextService;
import org.openfilz.dms.service.IndexService;
import org.openfilz.dms.utils.ReactiveStreamHelper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

    @Override
    public void indexDocument(FilePart filePart, Document document) {
        Mono.fromCallable(() ->  ReactiveStreamHelper.toInputStream(filePart.content())
                .flatMap(inputStream -> {
                    try {
                        var parser = new AutoDetectParser();
                        var handler = new BodyContentHandler(-1);
                        var metadata = new Metadata();
                        parser.parse(inputStream, handler, metadata, new ParseContext());
                        return Mono.just(handler.toString());
                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                }))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(text -> indexService.indexDocument(document, text))
                .doOnError(err ->
                        log.error("Text extraction error for {} : {}", document.getId(), err.getMessage()))
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
