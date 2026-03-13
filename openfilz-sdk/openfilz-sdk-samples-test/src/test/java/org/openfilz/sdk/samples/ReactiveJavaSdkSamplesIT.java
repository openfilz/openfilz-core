package org.openfilz.sdk.samples;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.openfilz.dms.DmsApiApplication;
import org.openfilz.sdk.reactive.ApiClient;
import org.openfilz.sdk.reactive.api.*;
import org.openfilz.sdk.reactive.model.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.test.context.TestConstructor;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Integration tests exercising the reactive Java SDK against a real OpenFilz API instance.
 * These tests mirror the samples/QuickStart.java code for the reactive SDK.
 */
@Testcontainers
@SpringBootTest(classes = DmsApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ReactiveJavaSdkSamplesIT extends SdkSamplesBaseConfig {

    private static UUID folderId;
    private static UUID targetFolderId;
    private static UUID fileId;
    private static UUID copyFileId;

    private ApiClient createClient() {
        ApiClient client = new ApiClient();
        client.setBasePath(getApiBaseUrl());
        return client;
    }

    @Test
    @Order(1)
    void createFolder() {
        FolderControllerApi folderApi = new FolderControllerApi(createClient());

        CreateFolderRequest request = new CreateFolderRequest();
        request.setName("Reactive SDK Samples");
        request.setParentId(null);

        StepVerifier.create(folderApi.createFolder(request))
                .assertNext(folder -> {
                    assertNotNull(folder.getId());
                    assertEquals("Reactive SDK Samples", folder.getName());
                    folderId = folder.getId();
                })
                .verifyComplete();
    }

    @Test
    @Order(2)
    void uploadFile() throws IOException {
        DocumentControllerApi documentApi = new DocumentControllerApi(createClient());

        Path tempFile = Files.createTempFile("sdk-reactive-sample-", ".txt");
        Files.writeString(tempFile, "Hello from OpenFilz Reactive Java SDK sample!");

        StepVerifier.create(documentApi.uploadDocument1(
                        tempFile.toFile(),
                        true,
                        folderId.toString(),
                        null
                ))
                .assertNext(response -> {
                    assertNotNull(response.getId());
                    assertNotNull(response.getName());
                    fileId = response.getId();
                })
                .verifyComplete();

        Files.deleteIfExists(tempFile);
    }

    @Test
    @Order(3)
    void listFolderContents() {
        FolderControllerApi folderApi = new FolderControllerApi(createClient());

        StepVerifier.create(folderApi.listFolder(folderId, false, false).collectList())
                .assertNext(contents -> {
                    assertFalse(contents.isEmpty());
                    assertTrue(contents.stream().anyMatch(item -> item.getId().equals(fileId)));
                })
                .verifyComplete();
    }

    @Test
    @Order(4)
    void getDocumentInfo() {
        DocumentControllerApi documentApi = new DocumentControllerApi(createClient());

        StepVerifier.create(documentApi.getDocumentInfo(fileId, true))
                .assertNext(info -> {
                    assertNotNull(info.getName());
                    assertEquals("text/plain", info.getContentType());
                })
                .verifyComplete();
    }

    @Test
    @Order(5)
    void updateMetadata() {
        DocumentControllerApi documentApi = new DocumentControllerApi(createClient());

        UpdateMetadataRequest metadata = new UpdateMetadataRequest();
        metadata.setMetadataToUpdate(Map.of(
                "project", "Reactive SDK Samples",
                "version", 1
        ));

        StepVerifier.create(documentApi.updateDocumentMetadata(fileId, metadata))
                .assertNext(result -> assertNotNull(result))
                .verifyComplete();
    }

    @Test
    @Order(6)
    void downloadFile() throws IOException {
        // The reactive SDK's downloadDocument() tries to deserialize binary content to java.io.File,
        // which fails for non-JSON content types. Use WebClient directly to verify the endpoint.
        // Stream the response via DataBufferUtils to a temp file instead of buffering in memory.
        WebClient webClient = WebClient.create(getApiBaseUrl());
        Path tempDownload = Files.createTempFile("sdk-reactive-download-", ".txt");

        StepVerifier.create(
                        DataBufferUtils.write(
                                        webClient.get()
                                                .uri("/api/v1/documents/{id}/download", fileId)
                                                .retrieve()
                                                .bodyToFlux(DataBuffer.class),
                                        tempDownload,
                                        StandardOpenOption.WRITE)
                                .then(Mono.fromCallable(() -> {
                                    // Verify content by streaming line-by-line — no full-file buffering
                                    try (BufferedReader reader = Files.newBufferedReader(tempDownload)) {
                                        return reader.readLine();
                                    }
                                })))
                .assertNext(firstLine ->
                        assertEquals("Hello from OpenFilz Reactive Java SDK sample!", firstLine))
                .verifyComplete();

        Files.deleteIfExists(tempDownload);
    }

    @Test
    @Order(7)
    void renameFile() {
        FileControllerApi fileApi = new FileControllerApi(createClient());

        RenameRequest rename = new RenameRequest();
        rename.setNewName("reactive-renamed-sample.txt");

        StepVerifier.create(fileApi.renameFile(fileId, rename))
                .assertNext(result -> assertEquals("reactive-renamed-sample.txt", result.getName()))
                .verifyComplete();
    }

    @Test
    @Order(8)
    void createTargetFolderAndCopyFile() {
        ApiClient client = createClient();
        FolderControllerApi folderApi = new FolderControllerApi(client);

        CreateFolderRequest request = new CreateFolderRequest();
        request.setName("Reactive SDK Samples Target");
        request.setParentId(null);

        StepVerifier.create(folderApi.createFolder(request))
                .assertNext(folder -> {
                    assertNotNull(folder.getId());
                    targetFolderId = folder.getId();
                })
                .verifyComplete();

        FileControllerApi fileApi = new FileControllerApi(client);

        CopyRequest copyRequest = new CopyRequest();
        copyRequest.setDocumentIds(List.of(fileId));
        copyRequest.setTargetFolderId(targetFolderId);

        StepVerifier.create(fileApi.copyFiles(copyRequest).collectList())
                .assertNext(copies -> {
                    assertFalse(copies.isEmpty());
                    assertNotNull(copies.getFirst().getCopyId());
                    copyFileId = copies.getFirst().getCopyId();
                })
                .verifyComplete();
    }

    @Test
    @Order(9)
    void moveFile() {
        FileControllerApi fileApi = new FileControllerApi(createClient());

        MoveRequest moveRequest = new MoveRequest();
        moveRequest.setDocumentIds(List.of(fileId));
        moveRequest.setTargetFolderId(targetFolderId);
        moveRequest.setAllowDuplicateFileNames(true);

        StepVerifier.create(fileApi.moveFiles(moveRequest))
                .verifyComplete();
    }

    @Test
    @Order(10)
    void toggleFavorite() {
        FavoritesApi favApi = new FavoritesApi(createClient());

        StepVerifier.create(favApi.toggleFavorite(fileId))
                .assertNext(isFavorite -> assertTrue(isFavorite))
                .verifyComplete();

        StepVerifier.create(favApi.isFavorite(fileId))
                .assertNext(status -> assertTrue(status))
                .verifyComplete();
    }

    @Test
    @Order(11)
    void dashboardStatistics() {
        DashboardApi dashboardApi = new DashboardApi(createClient());

        StepVerifier.create(dashboardApi.getDashboardStatistics())
                .assertNext(stats -> {
                    assertNotNull(stats);
                    assertTrue(stats.getTotalFiles() > 0);
                })
                .verifyComplete();
    }

    @Test
    @Order(12)
    void auditTrail() {
        // The generated SDK's Jackson OneOf discriminator mapping doesn't handle audit action types
        // properly (e.g. 'move' is not mapped to AuditLogDetailsOneOf subtypes).
        // Use WebClient directly to verify the audit endpoint works.
        WebClient webClient = WebClient.create(getApiBaseUrl());

        StepVerifier.create(webClient.get()
                        .uri("/api/v1/audit/{id}?sort={sort}", fileId, "DESC")
                        .retrieve()
                        .bodyToMono(String.class))
                .assertNext(body -> assertTrue(body.contains("\"action\""), "Response should contain audit log entries"))
                .verifyComplete();
    }

    @Test
    @Order(13)
    void deleteFiles() {
        ApiClient client = createClient();
        FavoritesApi favApi = new FavoritesApi(client);

        StepVerifier.create(favApi.removeFavorite(fileId))
                .verifyComplete();

        FileControllerApi fileApi = new FileControllerApi(client);
        DeleteRequest deleteRequest = new DeleteRequest();
        deleteRequest.setDocumentIds(List.of(fileId, copyFileId));

        StepVerifier.create(fileApi.deleteFiles(deleteRequest))
                .verifyComplete();

        FolderControllerApi folderApi = new FolderControllerApi(client);
        DeleteRequest folderDelete = new DeleteRequest();
        folderDelete.setDocumentIds(List.of(folderId, targetFolderId));

        StepVerifier.create(folderApi.deleteFolders(folderDelete))
                .verifyComplete();
    }
}
