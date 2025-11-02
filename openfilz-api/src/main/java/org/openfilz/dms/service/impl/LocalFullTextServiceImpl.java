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
    public void process(FilePart filePart, Document document) {
        Mono.fromCallable(() -> ReactiveStreamHelper.toInputStream(filePart.content())
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
                        log.error("Erreur d'extraction pour {} : {}", document.getId(), err.getMessage()))
                .subscribe();
    }


}
