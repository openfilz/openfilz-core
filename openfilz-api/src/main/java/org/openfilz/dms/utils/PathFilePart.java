package org.openfilz.dms.utils;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A FilePart implementation that streams content from a file Path.
 * This enables memory-efficient file handling without loading the entire file into memory.
 */
public class PathFilePart implements FilePart {

    private static final int BUFFER_SIZE = 8192;

    private final String name;
    private final String filename;
    private final Path path;

    public PathFilePart(String name, String filename, Path path) {
        this.name = name;
        this.filename = filename;
        this.path = path;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String filename() {
        return filename;
    }

    @Override
    public Flux<DataBuffer> content() {
        return DataBufferUtils.readAsynchronousFileChannel(
                () -> AsynchronousFileChannel.open(path, StandardOpenOption.READ),
                DefaultDataBufferFactory.sharedInstance,
                BUFFER_SIZE
        );
    }

    @Override
    public HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDispositionFormData(name, filename);
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return headers;
    }

    @Override
    public Mono<Void> transferTo(Path dest) {
        return Mono.fromCallable(() -> {
            Files.copy(path, dest);
            return null;
        }).then();
    }
}
