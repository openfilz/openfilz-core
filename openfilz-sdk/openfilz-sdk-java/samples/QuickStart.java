import org.openfilz.sdk.ApiClient;
import org.openfilz.sdk.ApiException;
import org.openfilz.sdk.Configuration;
import org.openfilz.sdk.api.*;
import org.openfilz.sdk.model.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenFilz Java SDK Quick Start
 *
 * This sample demonstrates the core document management operations:
 * - Folder creation
 * - File upload, download, rename, move, copy, delete
 * - Favorites management
 * - Metadata updates
 * - Dashboard statistics
 * - Audit trail
 *
 * Prerequisites:
 *   - A running OpenFilz API instance (default: http://localhost:8081)
 *   - Maven dependency: org.openfilz:openfilz-sdk-java
 */
public class QuickStart {

    public static void main(String[] args) throws ApiException, IOException {
        // ──── 1. Configure the SDK client ────────────────────────────────
        ApiClient client = Configuration.getDefaultApiClient();
        client.setBasePath("http://localhost:8081");

        // For authenticated instances, configure Bearer token:
        // HttpBearerAuth auth = (HttpBearerAuth) client.getAuthentication("keycloak_auth");
        // auth.setBearerToken("your-access-token");

        FolderControllerApi folderApi = new FolderControllerApi(client);
        DocumentControllerApi documentApi = new DocumentControllerApi(client);
        FileControllerApi fileApi = new FileControllerApi(client);
        FavoritesApi favApi = new FavoritesApi(client);
        DashboardApi dashboardApi = new DashboardApi(client);
        AuditControllerApi auditApi = new AuditControllerApi(client);

        // ──── 2. Create a folder ─────────────────────────────────────────
        CreateFolderRequest folderRequest = new CreateFolderRequest();
        folderRequest.setName("Quick Start Demo");
        folderRequest.setParentId(null); // root folder

        FolderResponse folder = folderApi.createFolder(folderRequest);
        UUID folderId = folder.getId();
        System.out.println("Created folder: " + folder.getName() + " (id=" + folderId + ")");

        // ──── 3. Upload a file into the folder ───────────────────────────
        Path tempFile = Files.createTempFile("demo-", ".txt");
        Files.writeString(tempFile, "Hello from OpenFilz SDK!");

        UploadResponse uploaded = documentApi.uploadDocument1(
                tempFile.toFile(),
                true,                       // allowDuplicateFileNames
                folderId.toString(),         // parentFolderId
                null                         // metadata JSON (optional)
        );
        UUID fileId = uploaded.getId();
        System.out.println("Uploaded: " + uploaded.getName() + " (id=" + fileId + ")");

        // ──── 4. List folder contents ────────────────────────────────────
        List<FolderElementInfo> contents = folderApi.listFolder(folderId, false, false);
        System.out.println("Folder contains " + contents.size() + " item(s):");
        for (FolderElementInfo item : contents) {
            System.out.println("  " + item.getType() + ": " + item.getName());
        }

        // ──── 5. Get document info ───────────────────────────────────────
        DocumentInfo info = documentApi.getDocumentInfo(fileId, true);
        System.out.println("Document info: name=" + info.getName()
                + ", contentType=" + info.getContentType()
                + ", size=" + info.getSize());

        // ──── 6. Update metadata ─────────────────────────────────────────
        UpdateMetadataRequest metadataReq = new UpdateMetadataRequest();
        metadataReq.setMetadataToUpdate(Map.of(
                "project", "Quick Start",
                "classification", "demo"
        ));
        documentApi.updateDocumentMetadata(fileId, metadataReq);
        System.out.println("Metadata updated");

        // ──── 7. Download the file ───────────────────────────────────────
        // Stream-based reading — avoids loading entire file into memory
        File downloaded = documentApi.downloadDocument(fileId);
        try (BufferedReader reader = new BufferedReader(new FileReader(downloaded))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("Downloaded content: " + line);
            }
        }

        // ──── 8. Rename the file ─────────────────────────────────────────
        RenameRequest rename = new RenameRequest();
        rename.setNewName("renamed-demo.txt");
        ElementInfo renamed = fileApi.renameFile(fileId, rename);
        System.out.println("Renamed to: " + renamed.getName());

        // ──── 9. Create a target folder and copy the file ────────────────
        CreateFolderRequest targetReq = new CreateFolderRequest();
        targetReq.setName("Quick Start Target");
        FolderResponse targetFolder = folderApi.createFolder(targetReq);
        UUID targetFolderId = targetFolder.getId();

        CopyRequest copyReq = new CopyRequest();
        copyReq.setDocumentIds(List.of(fileId));
        copyReq.setTargetFolderId(targetFolderId);
        List<CopyResponse> copies = fileApi.copyFiles(copyReq);
        UUID copyId = copies.getFirst().getCopyId();
        System.out.println("Copied file, new id=" + copyId);

        // ──── 10. Move the original file ─────────────────────────────────
        MoveRequest moveReq = new MoveRequest();
        moveReq.setDocumentIds(List.of(fileId));
        moveReq.setTargetFolderId(targetFolderId);
        moveReq.setAllowDuplicateFileNames(true);
        fileApi.moveFiles(moveReq);
        System.out.println("Moved file to target folder");

        // ──── 11. Toggle favorite ────────────────────────────────────────
        Boolean isFavorite = favApi.toggleFavorite(fileId);
        System.out.println("Favorite toggled: " + isFavorite);

        // ──── 12. Dashboard statistics ───────────────────────────────────
        DashboardStatisticsResponse stats = dashboardApi.getDashboardStatistics();
        System.out.println("Dashboard: totalFiles=" + stats.getTotalFiles()
                + ", totalFolders=" + stats.getTotalFolders());

        // ──── 13. Audit trail ────────────────────────────────────────────
        List<AuditLog> trail = auditApi.getAuditTrail(fileId, "DESC");
        System.out.println("Audit trail (" + trail.size() + " entries):");
        for (AuditLog entry : trail) {
            System.out.println("  " + entry.getAction() + " at " + entry.getTimestamp());
        }

        // ──── 14. Cleanup ────────────────────────────────────────────────
        favApi.removeFavorite(fileId);
        DeleteRequest deleteFiles = new DeleteRequest();
        deleteFiles.setDocumentIds(List.of(fileId, copyId));
        fileApi.deleteFiles(deleteFiles);

        DeleteRequest deleteFolders = new DeleteRequest();
        deleteFolders.setDocumentIds(List.of(folderId, targetFolderId));
        folderApi.deleteFolders(deleteFolders);
        System.out.println("Cleanup complete");

        Files.deleteIfExists(tempFile);
    }
}
