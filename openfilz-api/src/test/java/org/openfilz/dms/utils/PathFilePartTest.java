package org.openfilz.dms.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PathFilePartTest {

    private Path tempFile;

    @BeforeEach
    void setUp() throws IOException {
        tempFile = Files.createTempFile("path-filepart-test", ".txt");
        Files.writeString(tempFile, "test content");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(tempFile);
    }

    @Test
    void name_returnsName() {
        PathFilePart filePart = new PathFilePart("field", "file.txt", tempFile);
        assertEquals("field", filePart.name());
    }

    @Test
    void filename_returnsFilename() {
        PathFilePart filePart = new PathFilePart("field", "document.pdf", tempFile);
        assertEquals("document.pdf", filePart.filename());
    }

    @Test
    void headers_containsContentDispositionAndContentType() {
        PathFilePart filePart = new PathFilePart("file", "test.txt", tempFile);
        HttpHeaders headers = filePart.headers();

        assertNotNull(headers);
        assertNotNull(headers.getContentDisposition());
        assertEquals("test.txt", headers.getContentDisposition().getFilename());
        assertNotNull(headers.getContentType());
    }

    @Test
    void content_streamsFileData() {
        PathFilePart filePart = new PathFilePart("file", "test.txt", tempFile);

        StepVerifier.create(filePart.content())
                .expectNextMatches(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    return new String(bytes).contains("test content");
                })
                .verifyComplete();
    }

    @Test
    void transferTo_copiesFile() throws IOException {
        PathFilePart filePart = new PathFilePart("file", "test.txt", tempFile);
        Path destFile = Files.createTempFile("path-filepart-dest", ".txt");

        try {
            // Delete dest first since transferTo uses Files.copy which won't overwrite
            Files.deleteIfExists(destFile);

            StepVerifier.create(filePart.transferTo(destFile))
                    .verifyComplete();

            assertTrue(Files.exists(destFile));
            assertEquals("test content", Files.readString(destFile));
        } finally {
            Files.deleteIfExists(destFile);
        }
    }
}
