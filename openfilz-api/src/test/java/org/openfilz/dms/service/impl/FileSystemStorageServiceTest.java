package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.openfilz.dms.exception.StorageException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileSystemStorageServiceTest {

    private FileSystemStorageService service;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("fs-storage-test");
        service = new FileSystemStorageService(tempDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
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
    void constructor_createsDirectory() {
        assertTrue(Files.exists(tempDir));
        assertTrue(Files.isDirectory(tempDir));
    }

    @Test
    void saveFile_savesFileToDisk() {
        FilePart filePart = mockFilePart("test.txt", "hello world");

        StepVerifier.create(service.saveFile(filePart))
                .expectNextMatches(storagePath -> {
                    assertNotNull(storagePath);
                    assertTrue(storagePath.contains("test.txt"));
                    assertTrue(Files.exists(tempDir.resolve(storagePath)));
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void loadFile_whenExists_returnsResource() throws IOException {
        String filename = "loadable.txt";
        Files.write(tempDir.resolve(filename), "content".getBytes());

        StepVerifier.create(service.loadFile(filename))
                .expectNextMatches(resource -> {
                    assertTrue(resource.exists());
                    assertTrue(resource.isReadable());
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void loadFile_whenNotExists_returnsError() {
        StepVerifier.create(service.loadFile("non-existent.txt"))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void deleteFile_whenExists_deletesFile() throws IOException {
        String filename = "deletable.txt";
        Path file = tempDir.resolve(filename);
        Files.write(file, "content".getBytes());

        StepVerifier.create(service.deleteFile(filename))
                .verifyComplete();

        assertFalse(Files.exists(file));
    }

    @Test
    void deleteFile_whenNotExists_completesWithoutError() {
        // NoSuchFileException is caught and logged
        StepVerifier.create(service.deleteFile("non-existent.txt"))
                .verifyComplete();
    }

    @Test
    void copyFile_copiesFileWithNewName() throws IOException {
        String sourceFilename = "source.txt";
        Files.write(tempDir.resolve(sourceFilename), "original content".getBytes());

        StepVerifier.create(service.copyFile(sourceFilename))
                .expectNextMatches(newPath -> {
                    assertNotNull(newPath);
                    assertTrue(Files.exists(tempDir.resolve(newPath)));
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void getFileLength_returnsCorrectSize() throws IOException {
        String filename = "sized.txt";
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        Files.write(tempDir.resolve(filename), content);

        StepVerifier.create(service.getFileLength(filename))
                .expectNext((long) content.length)
                .verifyComplete();
    }

    @Test
    void createEmptyFile_createsFile() {
        String storagePath = "_tus/testfile.bin";

        StepVerifier.create(service.createEmptyFile(storagePath))
                .verifyComplete();

        assertTrue(Files.exists(tempDir.resolve(storagePath)));
    }

    @Test
    void createEmptyFile_whenAlreadyExists_completesWithoutError() throws IOException {
        String storagePath = "_tus/existing.bin";
        Path parentDir = tempDir.resolve("_tus");
        Files.createDirectories(parentDir);
        Files.createFile(parentDir.resolve("existing.bin"));

        StepVerifier.create(service.createEmptyFile(storagePath))
                .verifyComplete();
    }

    @Test
    void moveFile_movesFileToDestination() throws IOException {
        String source = "_tus/source.bin";
        String dest = "final/dest.txt";

        Path sourceDir = tempDir.resolve("_tus");
        Files.createDirectories(sourceDir);
        Files.write(sourceDir.resolve("source.bin"), "data".getBytes());

        StepVerifier.create(service.moveFile(source, dest))
                .verifyComplete();

        assertFalse(Files.exists(tempDir.resolve(source)));
        assertTrue(Files.exists(tempDir.resolve(dest)));
    }

    @Test
    void listFiles_returnsFileNames() throws IOException {
        Path subDir = tempDir.resolve("prefix");
        Files.createDirectories(subDir);
        Files.write(subDir.resolve("file1.txt"), "a".getBytes());
        Files.write(subDir.resolve("file2.txt"), "b".getBytes());

        StepVerifier.create(service.listFiles("prefix").collectList())
                .expectNextMatches(list -> {
                    assertEquals(2, list.size());
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void listFiles_whenPrefixNotExists_completesEmpty() {
        StepVerifier.create(service.listFiles("nonexistent"))
                .verifyComplete();
    }

    @Test
    void saveData_writesDataToFile() throws IOException {
        String storagePath = "_tus/data.bin";
        DataBuffer buffer = new DefaultDataBufferFactory().wrap("hello data".getBytes());
        Flux<DataBuffer> data = Flux.just(buffer);

        StepVerifier.create(service.saveData(storagePath, data))
                .verifyComplete();

        assertTrue(Files.exists(tempDir.resolve(storagePath)));
        byte[] written = Files.readAllBytes(tempDir.resolve(storagePath));
        assertEquals("hello data", new String(written));
    }

    @Test
    void appendData_appendsToExistingFile() throws IOException {
        String storagePath = "_tus/append.bin";
        Path parentDir = tempDir.resolve("_tus");
        Files.createDirectories(parentDir);
        Files.write(parentDir.resolve("append.bin"), "initial".getBytes());

        byte[] appendContent = "appended".getBytes();
        DataBuffer buffer = new DefaultDataBufferFactory().wrap(appendContent);
        Flux<DataBuffer> data = Flux.just(buffer);

        long initialOffset = "initial".length();

        StepVerifier.create(service.appendData(storagePath, data, initialOffset))
                .expectNextMatches(newOffset -> newOffset == initialOffset + appendContent.length)
                .verifyComplete();
    }

    @Test
    void getFileLength_nonExistentFile_throwsStorageException() {
        assertThrows(StorageException.class, () -> service.getFileLength("non-existent-file.txt"));
    }

    @Test
    void copyFile_nonExistentSource_emitsError() {
        StepVerifier.create(service.copyFile("non-existent-source.txt"))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void moveFile_nonExistentSource_emitsError() {
        StepVerifier.create(service.moveFile("non-existent-source.txt", "dest.txt"))
                .expectError(StorageException.class)
                .verify();
    }

    @Test
    void deleteFile_directoryInsteadOfFile_emitsError() throws IOException {
        // DirectoryNotEmptyException extends IOException (not NoSuchFileException)
        Path subDir = tempDir.resolve("subdir");
        Files.createDirectories(subDir);
        Files.write(subDir.resolve("child.txt"), "data".getBytes());

        StepVerifier.create(service.deleteFile("subdir"))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void listFiles_withNestedDirectories_returnsAllFiles() throws IOException {
        Path subDir = tempDir.resolve("nested");
        Files.createDirectories(subDir.resolve("deep"));
        Files.write(subDir.resolve("file1.txt"), "a".getBytes());
        Files.write(subDir.resolve("deep").resolve("file2.txt"), "b".getBytes());

        StepVerifier.create(service.listFiles("nested").collectList())
                .expectNextMatches(list -> {
                    assertEquals(2, list.size());
                    return true;
                })
                .verifyComplete();
    }

    private FilePart mockFilePart(String filename, String content) {
        FilePart filePart = mock(FilePart.class);
        when(filePart.filename()).thenReturn(filename);
        when(filePart.headers()).thenReturn(new HttpHeaders());
        when(filePart.transferTo(any(Path.class))).thenAnswer(invocation -> {
            Path dest = invocation.getArgument(0);
            Files.write(dest, content.getBytes());
            return Mono.empty();
        });
        return filePart;
    }
}
