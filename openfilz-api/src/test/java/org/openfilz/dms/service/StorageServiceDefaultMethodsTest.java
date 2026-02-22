package org.openfilz.dms.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

class StorageServiceDefaultMethodsTest {

    /**
     * Minimal implementation to test default methods only.
     */
    private final StorageService storageService = new StorageService() {
        @Override
        public Mono<String> saveFile(FilePart filePart) {
            return Mono.just("saved");
        }

        @Override
        public Mono<? extends Resource> loadFile(String storagePath) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> deleteFile(String storagePath) {
            return Mono.empty();
        }

        @Override
        public Mono<String> copyFile(String sourceStoragePath) {
            return Mono.empty();
        }

        @Override
        public Mono<Long> getFileLength(String storagePath) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> createEmptyFile(String storagePath) {
            return Mono.empty();
        }

        @Override
        public Mono<Long> appendData(String storagePath, Flux<DataBuffer> data, long offset) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> saveData(String storagePath, Flux<DataBuffer> data) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> moveFile(String sourcePath, String destPath) {
            return Mono.empty();
        }

        @Override
        public Flux<String> listFiles(String prefix) {
            return Flux.empty();
        }
    };

    @Test
    void getUniqueStorageFileName_withoutSeparator_prependsUuidAndSeparator() {
        String result = storageService.getUniqueStorageFileName("test.txt");
        assertTrue(result.endsWith("#test.txt"));
        // UUID is 36 chars + '#' + filename
        assertTrue(result.length() > "test.txt".length());
    }

    @Test
    void getUniqueStorageFileName_withSeparator_replacesPrefix() {
        String result = storageService.getUniqueStorageFileName("old-uuid#document.pdf");
        assertTrue(result.endsWith("#document.pdf"));
        assertFalse(result.startsWith("old-uuid"));
    }

    @Test
    void getOriginalFileName_extractsFilenameAfterSeparator() {
        assertEquals("report.pdf", storageService.getOriginalFileName("uuid-here#report.pdf"));
    }

    @Test
    void getTusDataPath_returnsCorrectPath() {
        assertEquals("_tus/upload-123.bin", storageService.getTusDataPath("upload-123"));
    }

    @Test
    void getTusMetadataPath_returnsCorrectPath() {
        assertEquals("_tus/upload-123.json", storageService.getTusMetadataPath("upload-123"));
    }

    @Test
    void deleteLatestVersion_returnsEmptyMono() {
        StepVerifier.create(storageService.deleteLatestVersion("any/path"))
                .verifyComplete();
    }

    @Test
    void replaceFile_delegatesToSaveFile() {
        FilePart filePart = org.mockito.Mockito.mock(FilePart.class);
        org.mockito.Mockito.when(filePart.filename()).thenReturn("new.txt");

        StepVerifier.create(storageService.replaceFile("old/path", filePart))
                .expectNext("saved")
                .verifyComplete();
    }
}
