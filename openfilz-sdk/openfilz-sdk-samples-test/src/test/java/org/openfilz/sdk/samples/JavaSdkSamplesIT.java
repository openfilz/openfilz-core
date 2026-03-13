package org.openfilz.sdk.samples;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.openfilz.dms.DmsApiApplication;
import org.openfilz.sdk.ApiClient;
import org.openfilz.sdk.ApiException;
import org.openfilz.sdk.Configuration;
import org.openfilz.sdk.api.*;
import org.openfilz.sdk.model.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Integration tests exercising the blocking Java SDK against a real OpenFilz API instance.
 * These tests mirror the samples/QuickStart.java code to ensure all sample code works correctly.
 */
@Testcontainers
@SpringBootTest(classes = DmsApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class JavaSdkSamplesIT extends SdkSamplesBaseConfig {

    private static UUID folderId;
    private static UUID targetFolderId;
    private static UUID fileId;
    private static UUID copyFileId;

    private ApiClient createClient() {
        ApiClient client = Configuration.getDefaultApiClient();
        client.setBasePath(getApiBaseUrl());
        return client;
    }

    @Test
    @Order(1)
    void createFolder() throws ApiException {
        FolderControllerApi folderApi = new FolderControllerApi(createClient());

        CreateFolderRequest request = new CreateFolderRequest();
        request.setName("SDK Samples");
        request.setParentId(null);

        FolderResponse folder = folderApi.createFolder(request);

        assertNotNull(folder);
        assertNotNull(folder.getId());
        assertEquals("SDK Samples", folder.getName());

        folderId = folder.getId();
    }

    @Test
    @Order(2)
    void uploadFile() throws ApiException, IOException {
        DocumentControllerApi documentApi = new DocumentControllerApi(createClient());

        Path tempFile = Files.createTempFile("sdk-sample-", ".txt");
        Files.writeString(tempFile, "Hello from OpenFilz Java SDK sample!");

        UploadResponse response = documentApi.uploadDocument1(
                tempFile.toFile(),
                true,
                folderId.toString(),
                null
        );

        assertNotNull(response);
        assertNotNull(response.getId());
        assertNotNull(response.getName());

        fileId = response.getId();

        Files.deleteIfExists(tempFile);
    }

    @Test
    @Order(3)
    void listFolderContents() throws ApiException {
        FolderControllerApi folderApi = new FolderControllerApi(createClient());

        List<FolderElementInfo> contents = folderApi.listFolder(folderId, false, false);

        assertNotNull(contents);
        assertFalse(contents.isEmpty());
        assertTrue(contents.stream().anyMatch(item -> item.getId().equals(fileId)));
    }

    @Test
    @Order(4)
    void getDocumentInfo() throws ApiException {
        DocumentControllerApi documentApi = new DocumentControllerApi(createClient());

        DocumentInfo info = documentApi.getDocumentInfo(fileId, true);

        assertNotNull(info);
        assertNotNull(info.getName());
        assertEquals("text/plain", info.getContentType());
    }

    @Test
    @Order(5)
    void updateMetadata() throws ApiException {
        DocumentControllerApi documentApi = new DocumentControllerApi(createClient());

        UpdateMetadataRequest metadata = new UpdateMetadataRequest();
        metadata.setMetadataToUpdate(Map.of(
                "project", "SDK Samples",
                "version", 1
        ));

        ElementInfo result = documentApi.updateDocumentMetadata(fileId, metadata);
        assertNotNull(result);
    }

    @Test
    @Order(6)
    void downloadFile() throws ApiException, IOException {
        DocumentControllerApi documentApi = new DocumentControllerApi(createClient());

        File downloaded = documentApi.downloadDocument(fileId);

        assertNotNull(downloaded);
        assertTrue(downloaded.exists());
        assertTrue(downloaded.length() > 0);

        // Stream-based content verification — avoids loading entire file into memory
        try (BufferedReader reader = new BufferedReader(new FileReader(downloaded))) {
            String firstLine = reader.readLine();
            assertEquals("Hello from OpenFilz Java SDK sample!", firstLine);
        }
    }

    @Test
    @Order(7)
    void renameFile() throws ApiException {
        FileControllerApi fileApi = new FileControllerApi(createClient());

        RenameRequest rename = new RenameRequest();
        rename.setNewName("renamed-sample.txt");

        ElementInfo result = fileApi.renameFile(fileId, rename);

        assertNotNull(result);
        assertEquals("renamed-sample.txt", result.getName());
    }

    @Test
    @Order(8)
    void createTargetFolderAndCopyFile() throws ApiException {
        ApiClient client = createClient();
        FolderControllerApi folderApi = new FolderControllerApi(client);

        CreateFolderRequest request = new CreateFolderRequest();
        request.setName("SDK Samples Target");
        request.setParentId(null);

        FolderResponse targetFolder = folderApi.createFolder(request);
        assertNotNull(targetFolder);
        targetFolderId = targetFolder.getId();

        FileControllerApi fileApi = new FileControllerApi(client);

        CopyRequest copyRequest = new CopyRequest();
        copyRequest.setDocumentIds(List.of(fileId));
        copyRequest.setTargetFolderId(targetFolderId);

        List<CopyResponse> copies = fileApi.copyFiles(copyRequest);

        assertNotNull(copies);
        assertFalse(copies.isEmpty());
        assertNotNull(copies.getFirst().getCopyId());

        copyFileId = copies.getFirst().getCopyId();
    }

    @Test
    @Order(9)
    void moveFile() throws ApiException {
        FileControllerApi fileApi = new FileControllerApi(createClient());

        MoveRequest moveRequest = new MoveRequest();
        moveRequest.setDocumentIds(List.of(fileId));
        moveRequest.setTargetFolderId(targetFolderId);
        moveRequest.setAllowDuplicateFileNames(true);

        fileApi.moveFiles(moveRequest);

        // Verify the file is now in the target folder
        FolderControllerApi folderApi = new FolderControllerApi(createClient());
        List<FolderElementInfo> targetContents = folderApi.listFolder(targetFolderId, true, false);
        assertTrue(targetContents.stream().anyMatch(item -> item.getId().equals(fileId)));
    }

    @Test
    @Order(10)
    void toggleFavorite() throws ApiException {
        FavoritesApi favApi = new FavoritesApi(createClient());

        Boolean isFavorite = favApi.toggleFavorite(fileId);
        assertTrue(isFavorite);

        Boolean status = favApi.isFavorite(fileId);
        assertTrue(status);
    }

    @Test
    @Order(11)
    void dashboardStatistics() throws ApiException {
        DashboardApi dashboardApi = new DashboardApi(createClient());

        DashboardStatisticsResponse stats = dashboardApi.getDashboardStatistics();

        assertNotNull(stats);
        assertTrue(stats.getTotalFiles() > 0);
    }

    @Test
    @Order(12)
    void auditTrail() throws Exception {
        // Use raw OkHttp call to verify the audit trail endpoint works.
        // The generated Gson OneOf deserialization in AuditLogDetailsOneOf has a known
        // infinite-recursion bug in validateJsonElement, so we bypass it here.
        ApiClient client = createClient();
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(getApiBaseUrl() + "/api/v1/audit/" + fileId + "?sort=DESC")
                .get()
                .build();
        try (okhttp3.Response response = client.getHttpClient().newCall(request).execute()) {
            assertEquals(200, response.code());
            assertNotNull(response.body());
            String body = response.body().string();
            assertTrue(body.contains("\"action\""), "Response should contain audit log entries");
        }
    }

    @Test
    @Order(13)
    void deleteFiles() throws ApiException {
        ApiClient client = createClient();
        FileControllerApi fileApi = new FileControllerApi(client);

        // Remove favorite first
        FavoritesApi favApi = new FavoritesApi(client);
        favApi.removeFavorite(fileId);

        // Delete both files
        DeleteRequest deleteRequest = new DeleteRequest();
        deleteRequest.setDocumentIds(List.of(fileId, copyFileId));
        fileApi.deleteFiles(deleteRequest);

        // Delete folders
        FolderControllerApi folderApi = new FolderControllerApi(client);
        DeleteRequest folderDelete = new DeleteRequest();
        folderDelete.setDocumentIds(List.of(folderId, targetFolderId));
        folderApi.deleteFolders(folderDelete);
    }
}
