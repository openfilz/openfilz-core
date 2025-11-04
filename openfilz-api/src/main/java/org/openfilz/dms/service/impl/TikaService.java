package org.openfilz.dms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ContentHandlerDecorator;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Service;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import reactor.core.publisher.Flux;

import java.io.InputStream;

/**
 * Extracts text from uploaded documents using Apache Tika and streams it as a Flux<String>.
 */
@Service
@Slf4j
public class TikaService {

    private static final int FRAGMENT_SIZE = 2048; // characters per emitted chunk

    private final AutoDetectParser parser = new AutoDetectParser();

    /**
     * Reactive streaming text extraction.
     *
     * @param dataBufferFlux the uploaded file content
     * @return Flux<String> of textual fragments
     */
    public Flux<String> extractTextAsFlux(Flux<DataBuffer> dataBufferFlux) {
        log.debug("Extracting text as flux");
        return DataBufferUtils.join(dataBufferFlux)
                .flatMapMany(dataBuffer -> {
                    InputStream inputStream = dataBuffer.asInputStream();

                    return Flux.<String>create(sink -> {
                                Thread parserThread = Thread.ofVirtual().start(() -> {
                                    try {
                                        Metadata metadata = new Metadata();
                                        ParseContext context = new ParseContext();
                                        ContentHandler handler = new StreamingContentHandler(sink);

                                        parser.parse(inputStream, handler, metadata, context);
                                    } catch (Exception e) {
                                        sink.error(e);
                                    } finally {
                                        sink.complete();
                                        DataBufferUtils.release(dataBuffer);
                                        try {
                                            inputStream.close();
                                        } catch (Exception ignored) {
                                            log.error("ignored exception", ignored);
                                        }
                                    }
                                });

                                sink.onCancel(parserThread::interrupt);
                            })
                            .doOnError(e -> log.error("Error during Tika parsing", e))
                            .doOnComplete(() -> log.info("Tika parsing completed"));

                });
    }

    /**
     * Custom SAX handler that streams text fragments directly into a FluxSink.
     */
    static class StreamingContentHandler extends ContentHandlerDecorator {
        private final reactor.core.publisher.FluxSink<String> sink;
        private final StringBuilder buffer = new StringBuilder(FRAGMENT_SIZE);

        StreamingContentHandler(reactor.core.publisher.FluxSink<String> sink) {
            this.sink = sink;
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            buffer.append(ch, start, length);
            if (buffer.length() >= FRAGMENT_SIZE) {
                emit();
            }
        }

        @Override
        public void endDocument() throws SAXException {
            emit(); // flush remaining buffer
            super.endDocument();
        }

        private void emit() {
            if (buffer.isEmpty()) return;
            String chunk = buffer.toString();
            buffer.setLength(0);
            sink.next(chunk);
        }
    }
}
