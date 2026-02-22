package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.ThumbnailProperties;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FileSystemThumbnailStorageServiceTest {

    private FileSystemThumbnailStorageService service;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("thumbnail-test");
        ThumbnailProperties props = new ThumbnailProperties();
        props.getStorage().setUseMainStorage(true);

        service = new FileSystemThumbnailStorageService(props, tempDir.toString());
        service.init();
    }

    @AfterEach
    void tearDown() throws IOException {
        // Recursively delete temp directory
        if (Files.exists(tempDir)) {
            Files.walkFileTree(tempDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    @Test
    void init_createsThumbnailsDirectory() {
        Path thumbnailsDir = tempDir.resolve("thumbnails");
        assertTrue(Files.exists(thumbnailsDir));
        assertTrue(Files.isDirectory(thumbnailsDir));
    }

    @Test
    void saveThumbnail_writesFileSuccessfully() {
        UUID docId = UUID.randomUUID();
        byte[] data = {1, 2, 3, 4, 5};

        StepVerifier.create(service.saveThumbnail(docId, data, "png"))
                .verifyComplete();

        Path expected = tempDir.resolve("thumbnails").resolve(docId.toString());
        assertTrue(Files.exists(expected));
    }

    @Test
    void saveThumbnail_overwritesExistingFile() throws IOException {
        UUID docId = UUID.randomUUID();
        byte[] data1 = {1, 2, 3};
        byte[] data2 = {4, 5, 6, 7};

        // Save first version
        StepVerifier.create(service.saveThumbnail(docId, data1, "png"))
                .verifyComplete();

        // Overwrite with second version
        StepVerifier.create(service.saveThumbnail(docId, data2, "png"))
                .verifyComplete();

        Path file = tempDir.resolve("thumbnails").resolve(docId.toString());
        byte[] content = Files.readAllBytes(file);
        assertArrayEquals(data2, content);
    }

    @Test
    void loadThumbnail_whenExists_returnsBytes() throws IOException {
        UUID docId = UUID.randomUUID();
        byte[] data = {10, 20, 30};
        Path file = tempDir.resolve("thumbnails").resolve(docId.toString());
        Files.write(file, data);

        StepVerifier.create(service.loadThumbnail(docId))
                .expectNextMatches(bytes -> {
                    assertArrayEquals(data, bytes);
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void loadThumbnail_whenNotExists_completesEmpty() {
        UUID docId = UUID.randomUUID();

        // Mono.fromCallable returning null results in an empty Mono
        StepVerifier.create(service.loadThumbnail(docId))
                .verifyComplete();
    }

    @Test
    void deleteThumbnail_whenExists_deletesFile() throws IOException {
        UUID docId = UUID.randomUUID();
        Path file = tempDir.resolve("thumbnails").resolve(docId.toString());
        Files.write(file, new byte[]{1, 2, 3});

        StepVerifier.create(service.deleteThumbnail(docId))
                .verifyComplete();

        assertFalse(Files.exists(file));
    }

    @Test
    void deleteThumbnail_whenNotExists_completesWithoutError() {
        UUID docId = UUID.randomUUID();

        StepVerifier.create(service.deleteThumbnail(docId))
                .verifyComplete();
    }

    @Test
    void copyThumbnail_whenSourceExists_copiesFile() throws IOException {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        byte[] data = {1, 2, 3, 4};
        Files.write(tempDir.resolve("thumbnails").resolve(sourceId.toString()), data);

        StepVerifier.create(service.copyThumbnail(sourceId, targetId))
                .verifyComplete();

        Path targetFile = tempDir.resolve("thumbnails").resolve(targetId.toString());
        assertTrue(Files.exists(targetFile));
        assertArrayEquals(data, Files.readAllBytes(targetFile));
    }

    @Test
    void copyThumbnail_whenSourceNotExists_completesWithoutError() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        StepVerifier.create(service.copyThumbnail(sourceId, targetId))
                .verifyComplete();

        assertFalse(Files.exists(tempDir.resolve("thumbnails").resolve(targetId.toString())));
    }

    @Test
    void thumbnailExists_whenExists_returnsTrue() throws IOException {
        UUID docId = UUID.randomUUID();
        Files.write(tempDir.resolve("thumbnails").resolve(docId.toString()), new byte[]{1});

        StepVerifier.create(service.thumbnailExists(docId))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void thumbnailExists_whenNotExists_returnsFalse() {
        UUID docId = UUID.randomUUID();

        StepVerifier.create(service.thumbnailExists(docId))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void constructorWithNonMainStorage_usesCustomBasePath() throws IOException {
        Path customDir = Files.createTempDirectory("custom-thumbnail");
        try {
            ThumbnailProperties props = new ThumbnailProperties();
            props.getStorage().setUseMainStorage(false);
            props.getStorage().getLocal().setBasePath(customDir.toString());

            FileSystemThumbnailStorageService customService =
                    new FileSystemThumbnailStorageService(props, tempDir.toString());
            customService.init();

            assertTrue(Files.exists(customDir.resolve("thumbnails")));
        } finally {
            Files.walkFileTree(customDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    @Test
    void constructorWithNonMainStorage_andBlankBasePath_fallsBackToMainPath() {
        ThumbnailProperties props = new ThumbnailProperties();
        props.getStorage().setUseMainStorage(false);
        props.getStorage().getLocal().setBasePath("");

        FileSystemThumbnailStorageService fallbackService =
                new FileSystemThumbnailStorageService(props, tempDir.toString());
        fallbackService.init();

        assertTrue(Files.exists(tempDir.resolve("thumbnails")));
    }

    @Test
    void constructorWithNonMainStorage_andNullBasePath_fallsBackToMainPath() {
        ThumbnailProperties props = new ThumbnailProperties();
        props.getStorage().setUseMainStorage(false);
        props.getStorage().getLocal().setBasePath(null);

        FileSystemThumbnailStorageService nullService =
                new FileSystemThumbnailStorageService(props, tempDir.toString());
        nullService.init();

        assertTrue(Files.exists(tempDir.resolve("thumbnails")));
    }

    @Test
    void init_withInvalidPath_throwsRuntimeException() {
        ThumbnailProperties props = new ThumbnailProperties();
        props.getStorage().setUseMainStorage(true);

        // NUL is not a valid path component on Windows, \0 on Unix
        FileSystemThumbnailStorageService badService =
                new FileSystemThumbnailStorageService(props, tempDir.resolve("thumbnails").toString());

        // Make the thumbnails directory read-only to trigger IOException
        Path thumbnailsDir = tempDir.resolve("thumbnails").resolve("thumbnails");
        // Use an impossible nested path to trigger creation failure
        try {
            // Create a file where a directory needs to be created
            Path blocker = tempDir.resolve("blocker");
            Files.write(blocker, "not a directory".getBytes());
            FileSystemThumbnailStorageService blockedService =
                    new FileSystemThumbnailStorageService(props, blocker.toString());
            // init() should throw because it can't create directories under a file
            assertThrows(RuntimeException.class, blockedService::init);
        } catch (IOException e) {
            fail("Test setup failed: " + e.getMessage());
        }
    }

    @Test
    void saveThumbnail_withReadOnlyDirectory_emitsError() throws IOException {
        // Test error path by writing to a non-existent nested path that would fail
        UUID docId = UUID.randomUUID();
        byte[] data = {1, 2, 3};

        // Create a service pointing to a file (not a directory) to trigger IOException
        ThumbnailProperties props = new ThumbnailProperties();
        props.getStorage().setUseMainStorage(true);
        Path blocker = Files.createTempFile("blocker-thumb", ".txt");
        try {
            FileSystemThumbnailStorageService badService =
                    new FileSystemThumbnailStorageService(props, blocker.toString());
            // Don't call init() - set up manually to avoid the init failure
            // The saveThumbnail will fail because thumbnailsPath points to a file/thumbnails

            StepVerifier.create(badService.saveThumbnail(docId, data, "png"))
                    .expectError(RuntimeException.class)
                    .verify();
        } finally {
            Files.deleteIfExists(blocker);
        }
    }
}
