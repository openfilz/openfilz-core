package org.openfilz.dms.utils;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;

/**
 * An in-memory implementation of FilePart that wraps a byte array.
 * Useful for creating documents from templates without needing actual file uploads.
 */
public class InMemoryFilePart implements FilePart {

    private final String filename;
    private final byte[] content;
    private final String contentType;

    public InMemoryFilePart(String filename, byte[] content, String contentType) {
        this.filename = filename;
        this.content = content;
        this.contentType = contentType;
    }

    @Override
    public String filename() {
        return filename;
    }

    @Override
    public Mono<Void> transferTo(Path dest) {
        return DataBufferUtils.write(content(), dest);
    }

    @Override
    public String name() {
        return "file";
    }

    @Override
    public HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentLength(content.length);
        if (contentType != null) {
            headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        }
        return headers;
    }

    @Override
    public Flux<DataBuffer> content() {
        DataBuffer buffer = DefaultDataBufferFactory.sharedInstance.wrap(content);
        return Flux.just(buffer);
    }
}
