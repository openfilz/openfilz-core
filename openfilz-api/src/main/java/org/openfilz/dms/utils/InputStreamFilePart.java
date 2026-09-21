package org.openfilz.dms.utils;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A FilePart whose content is produced by a re-openable blocking {@link InputStream} of known
 * length (e.g. an entry of a ZIP archive already on disk). Each call to {@link #content()} or
 * {@link #transferTo(Path)} opens a fresh stream, so the part can be read more than once.
 * <p>
 * Storage implementations that can upload a stream of known length in one request (MinIO) detect
 * this type and skip the piped, multipart path used for streams of unknown length.
 */
public class InputStreamFilePart implements FilePart {

    private static final int BUFFER_SIZE = 64 * 1024;

    @FunctionalInterface
    public interface StreamOpener {
        InputStream open() throws IOException;
    }

    private final String filename;
    private final long contentLength;
    private final StreamOpener opener;

    public InputStreamFilePart(String filename, long contentLength, StreamOpener opener) {
        this.filename = filename;
        this.contentLength = contentLength;
        this.opener = opener;
    }

    /** Opens a new stream over the content; the caller closes it. */
    public InputStream openStream() throws IOException {
        return opener.open();
    }

    /** Exact length of the content in bytes. */
    public long contentLength() {
        return contentLength;
    }

    @Override
    public String name() {
        return "file";
    }

    @Override
    public String filename() {
        return filename;
    }

    @Override
    public Flux<DataBuffer> content() {
        return DataBufferUtils.readInputStream(opener::open, DefaultDataBufferFactory.sharedInstance, BUFFER_SIZE)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDispositionFormData(name(), filename);
        // Octet-stream: FileUtils.getContentType then derives the type from the file extension.
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentLength(contentLength);
        return headers;
    }

    @Override
    public Mono<Void> transferTo(Path dest) {
        return Mono.fromCallable(() -> {
            try (InputStream in = opener.open()) {
                Files.copy(in, dest);
            }
            return dest;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
