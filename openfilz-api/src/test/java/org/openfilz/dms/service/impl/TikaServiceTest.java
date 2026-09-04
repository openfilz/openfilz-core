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
    void processResource_pdf_reportsTheFileMetadata() {
        Resource resource = new org.springframework.core.io.ClassPathResource("pdf-example.pdf");
        java.util.concurrent.atomic.AtomicReference<org.apache.tika.metadata.Metadata> seen = new java.util.concurrent.atomic.AtomicReference<>();

        StepVerifier.create(service.processResource(tempFile, Mono.just(resource), seen::set).collectList())
                .assertNext(chunks -> assertTrue(String.join("", chunks).length() > 0))
                .verifyComplete();

        assertTrue(seen.get() != null, "the metadata callback must run after the parse");
        org.openfilz.dms.service.insight.TikaFileMetadata metadata = org.openfilz.dms.service.insight.TikaFileMetadata.from(seen.get());
        assertTrue(metadata.pageCount() != null && metadata.pageCount() >= 1,
                "a PDF always reports its page count, got: " + metadata);
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
