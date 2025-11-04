package org.openfilz.dms.utils;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.*;

/**
 * Helper class to convert a Flux<DataBuffer> into an InputStream.
 * This is particularly useful for integrating reactive streams with blocking APIs
 * that expect an InputStream (e.g., Apache Tika).
 */
@Slf4j
@UtilityClass
public class ReactiveStreamHelper {

    /**
     * Converts a Flux<DataBuffer> into a Mono<InputStream>.
     * The InputStream will be backed by a PipedInputStream/PipedOutputStream pair.
     * The Flux will write data to the PipedOutputStream on a dedicated thread,
     * while the PipedInputStream can be read on another thread (often a blocking one).
     *
     * IMPORTANT: The caller is responsible for closing the returned InputStream.
     * Closing the InputStream will also cause the underlying PipedOutputStream to close
     * and the Flux subscription to be cancelled.
     *
     * @param dataBufferFlux The Flux of DataBuffer to convert.
     * @return A Mono emitting the InputStream.
     */
    public static Mono<InputStream> toInputStream(Flux<DataBuffer> dataBufferFlux) {
        return Mono.fromCallable(() -> {
            PipedInputStream pis = new PipedInputStream();
            PipedOutputStream pos = new PipedOutputStream(pis);

            // Subscribe to the Flux on a boundedElastic scheduler to avoid blocking the main event loop.
            // This ensures that writing to the PipedOutputStream happens on a dedicated thread.
            dataBufferFlux
                .doOnNext(dataBuffer -> {
                    try {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes); // Read data from DataBuffer into the byte array
                        pos.write(bytes);       // Write the byte array to the PipedOutputStream

                    } catch (IOException e) {
                        // If the InputStream side is closed prematurely, an IOException might occur.
                        // We need to propagate this as an error to the Flux.
                        throw new RuntimeException("Error writing to PipedOutputStream", e);
                    }
                })
                .doOnError(error -> {
                    closePipedOutputStream(pos);
                })
                .doOnComplete(() -> {
                    closePipedOutputStream(pos);
                })
                .doOnCancel(() -> {
                    closePipedOutputStream(pos);
                })
                .subscribeOn(Schedulers.boundedElastic()) // Crucial: Run this on a blocking-friendly scheduler
                .subscribe(); // Start the subscription

            return pis; // Return the InputStream immediately
        });
    }

    private static void closePipedOutputStream(PipedOutputStream pos) {
        try {
            pos.close(); // Close the output stream on error
        } catch (IOException e) {
            log.error("Error closing PipedOutputStream", e);
        }
    }

}