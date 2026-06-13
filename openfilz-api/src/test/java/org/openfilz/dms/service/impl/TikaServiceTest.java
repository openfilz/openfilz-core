package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class TikaServiceTest {

    private final TikaService service = new TikaService();
    private Path tempFile;

    @BeforeEach
    void setUp() throws IOException {
        tempFile = Files.createTempFile("tika-test", ".tmp");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(tempFile);
    }

    @Test
    void processResource_plainText_emitsExtractedText() {
        Resource resource = new ByteArrayResource("Hello Tika extraction world".getBytes());

        StepVerifier.create(service.processResource(tempFile, Mono.just(resource)).collectList())
                .assertNext(chunks -> assertTrue(String.join("", chunks).contains("Hello Tika")))
                .verifyComplete();
    }

    @Test
    void processResource_unreadableResource_propagatesError() throws IOException {
        Resource resource = mock(Resource.class);
        when(resource.getDescription()).thenReturn("broken-resource");
        when(resource.getInputStream()).thenThrow(new IOException("cannot read"));

        StepVerifier.create(service.processResource(tempFile, Mono.just(resource)))
                .expectError()
                .verify();
    }
}
