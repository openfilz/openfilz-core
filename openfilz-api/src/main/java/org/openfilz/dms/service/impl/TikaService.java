package org.openfilz.dms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.openfilz.dms.utils.FluxSinkContentHandler;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.xml.sax.ContentHandler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Extracts text from uploaded documents using Apache Tika and streams it as a Flux<String>.
 */
@Service
@Slf4j
public class TikaService {

    private final Parser parser = new AutoDetectParser();

    private final ExecutorService tikaExecutor = Executors.newVirtualThreadPerTaskExecutor(); // Or a custom bounded executor

    /**
     * Parses a Resource (e.g., from a file upload, classpath, or URL) into a Flux of strings
     * using Tika. This implementation is memory-safe for large PDFs by first spooling the
     * resource to a temporary file on disk, allowing for random access without loading the
     * entire file into the JVM heap.
     *
     * @param stableTempFile
     * @param resourceMono A Mono emitting the Resource to be parsed.
     * @return A Flux that emits text chunks as they are parsed from the document.
     */
    public Flux<String> processResource(Path stableTempFile, Mono<? extends Resource> resourceMono) {
        // First, we need the actual Resource. The flatMap operator lets us work within the Mono.
        return resourceMono.flatMap(resource -> {
            log.info("Starting memory-safe processing for resource: {}", resource.getDescription());

            // The usingWhen operator ensures our temporary file is created and cleaned up reliably.
            // It will produce a Mono<Flux<String>> which we will unwrap later.
            return copyResourceToPath(resource, stableTempFile)
                .then(
                    // Step B: Once the copy is complete, create the Tika parsing Flux.
                    Mono.fromCallable(() -> Flux.<String>create(sink -> {
                        tikaExecutor.submit(() -> {
                            log.info("Starting Tika parsing from stable file path [{}].", stableTempFile);
                            try (TikaInputStream tikaStream = TikaInputStream.get(stableTempFile)) {
                                ContentHandler handler = new FluxSinkContentHandler(sink); // Your streaming handler
                                parser.parse(tikaStream, handler, new Metadata(), new ParseContext());
                                log.info("Tika parsing completed for stable file [{}].", stableTempFile);
                                sink.complete();
                            } catch (Exception e) {
                                log.error("Error during Tika parsing of stable file [{}].", stableTempFile, e);
                                sink.error(e);
                            }
                        });
                    }))
                );

            })
            // Finally, unwrap the Mono<Flux<String>> into the Flux<String> required by the method signature.
            .flatMapMany(flux -> flux);
    }

    /**
     * A helper method that copies a Spring Resource to a Path reactively.
     * It performs the blocking I/O on the boundedElastic scheduler.
     */
    private Mono<Void> copyResourceToPath(Resource resource, Path destination) {
        return Mono.fromRunnable(() -> {
            try (InputStream inputStream = resource.getInputStream()) {
                Files.copy(inputStream, destination, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // Wrap checked exception for reactive chain
                throw new UncheckedIOException("Failed to copy resource " + resource.getDescription() + " to " + destination, e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }



}
