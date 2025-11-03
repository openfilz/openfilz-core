package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.openfilz.dms.service.TikaService;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;

@Service
@RequiredArgsConstructor
public class TikaServiceImpl implements TikaService {

    public Flux<String> extractTextAsFlux(InputStream inputStream) {
        return Flux.<String>create(sink -> {
            try {
                AutoDetectParser parser = new AutoDetectParser();
                Metadata metadata = new Metadata();

                parser.parse(inputStream, new DefaultHandler() {
                    @Override
                    public void characters(char[] ch, int start, int length) throws SAXException {
                        String fragment = new String(ch, start, length).trim();
                        if (!fragment.isEmpty()) {
                            sink.next(fragment);
                        }
                    }

                    @Override
                    public void endDocument() throws SAXException {
                        sink.complete();
                    }
                }, metadata, new ParseContext());

            } catch (Exception e) {
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

}
