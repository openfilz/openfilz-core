import org.openfilz.sdk.reactive.ApiClient;
import org.openfilz.sdk.reactive.api.*;
import org.openfilz.sdk.reactive.model.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenFilz Reactive Java SDK Quick Start
 *
 * This sample demonstrates the core document management operations using
 * the reactive (non-blocking) programming model with Mono and Flux.
 *
 * Prerequisites:
 *   - A running OpenFilz API instance (default: http://localhost:8081)
 *   - Maven dependency: org.openfilz:openfilz-sdk-java-reactive
 *   - Spring WebFlux on the classpath
 */
public class QuickStart {

    public static void main(String[] args) throws IOException {
        // ──── 1. Configure the reactive SDK client ───────────────────────
        ApiClient apiClient = new ApiClient(
                WebClient.builder()
                        .baseUrl("http://localhost:8081")
                        // For authenticated instances:
                        // .defaultHeader("Authorization", "Bearer " + accessToken)
                        .build()
        );

        FolderControllerApi folderApi = new FolderControllerApi(apiClient);
        DocumentControllerApi documentApi = new DocumentControllerApi(apiClient);
        FileControllerApi fileApi = new FileControllerApi(apiClient);
        FavoritesApi favApi = new FavoritesApi(apiClient);
        DashboardApi dashboardApi = new DashboardApi(apiClient);
        AuditControllerApi auditApi = new AuditControllerApi(apiClient);

        // Create a temp file for upload
        Path tempFile = Files.createTempFile("demo-reactive-", ".txt");
        Files.writeString(tempFile, "Hello from OpenFilz Reactive SDK!");

        // ──── 2. Reactive composition: full workflow ─────────────────────
        // This demonstrates chaining reactive operations with flatMap

        CreateFolderRequest folderReq = new CreateFolderRequest();
        folderReq.setName("Reactive Quick Start Demo");

        folderApi.createFolder(folderReq)
                .flatMap(folder -> {
                    System.out.println("Created folder: " + folder.getName());

                    // Upload a file into the folder
                    return documentApi.uploadDocument1(
                                    tempFile.toFile(), true, folder.getId().toString(), null)
                            .map(uploaded -> new Object[]{folder.getId(), uploaded.getId(), uploaded.getName()});
                })
                .flatMap(ids -> {
                    UUID folderId = (UUID) ids[0];
                    UUID fileId = (UUID) ids[1];
                    System.out.println("Uploaded: " + ids[2] + " (id=" + fileId + ")");

                    // List folder contents
                    return folderApi.listFolder(folderId, false, false)
                            .collectList()
                            .doOnNext(contents -> {
                                System.out.println("Folder contains " + contents.size() + " item(s)");
                                contents.forEach(item ->
                                        System.out.println("  " + item.getType() + ": " + item.getName()));
                            })
                            .thenReturn(new Object[]{folderId, fileId});
                })
                .flatMap(ids -> {
                    UUID folderId = (UUID) ids[0];
                    UUID fileId = (UUID) ids[1];

                    // Get document info
                    return documentApi.getDocumentInfo(fileId, true)
                            .doOnNext(info -> System.out.println("Document: " + info.getName()
                                    + ", type=" + info.getContentType()))
                            .thenReturn(new Object[]{folderId, fileId});
                })
                .flatMap(ids -> {
                    UUID folderId = (UUID) ids[0];
                    UUID fileId = (UUID) ids[1];

                    // Update metadata
                    UpdateMetadataRequest metadataReq = new UpdateMetadataRequest();
                    metadataReq.setMetadataToUpdate(Map.of("project", "Reactive Demo"));

                    return documentApi.updateDocumentMetadata(fileId, metadataReq)
                            .doOnNext(result -> System.out.println("Metadata updated"))
                            .thenReturn(new Object[]{folderId, fileId});
                })
                .flatMap(ids -> {
                    UUID folderId = (UUID) ids[0];
                    UUID fileId = (UUID) ids[1];

                    // Download the file
                    return documentApi.downloadDocument(fileId)
                            .doOnNext(file -> System.out.println("Downloaded: " + file.getName()))
                            .thenReturn(new Object[]{folderId, fileId});
                })
                .flatMap(ids -> {
                    UUID folderId = (UUID) ids[0];
                    UUID fileId = (UUID) ids[1];

                    // Rename the file
                    RenameRequest rename = new RenameRequest();
                    rename.setNewName("reactive-renamed.txt");

                    return fileApi.renameFile(fileId, rename)
                            .doOnNext(r -> System.out.println("Renamed to: " + r.getName()))
                            .thenReturn(new Object[]{folderId, fileId});
                })
                .flatMap(ids -> {
                    UUID folderId = (UUID) ids[0];
                    UUID fileId = (UUID) ids[1];

                    // Toggle favorite
                    return favApi.toggleFavorite(fileId)
                            .doOnNext(fav -> System.out.println("Favorite: " + fav))
                            .thenReturn(new Object[]{folderId, fileId});
                })
                .flatMap(ids -> {
                    UUID fileId = (UUID) ids[1];

                    // Dashboard statistics
                    return dashboardApi.getDashboardStatistics()
                            .doOnNext(stats -> System.out.println("Files: " + stats.getTotalFiles()
                                    + ", Folders: " + stats.getTotalFolders()))
                            .thenReturn(ids);
                })
                .flatMap(ids -> {
                    UUID fileId = (UUID) ids[1];

                    // Audit trail
                    return auditApi.getAuditTrail(fileId, "DESC")
                            .collectList()
                            .doOnNext(trail -> System.out.println("Audit entries: " + trail.size()))
                            .thenReturn(ids);
                })
                .flatMap(ids -> {
                    UUID folderId = (UUID) ids[0];
                    UUID fileId = (UUID) ids[1];

                    // Cleanup: remove favorite, delete file, delete folder
                    return favApi.removeFavorite(fileId)
                            .then(Mono.defer(() -> {
                                DeleteRequest deleteReq = new DeleteRequest();
                                deleteReq.setDocumentIds(List.of(fileId));
                                return fileApi.deleteFiles(deleteReq);
                            }))
                            .then(Mono.defer(() -> {
                                DeleteRequest folderDelete = new DeleteRequest();
                                folderDelete.setDocumentIds(List.of(folderId));
                                return folderApi.deleteFolders(folderDelete);
                            }))
                            .doOnTerminate(() -> System.out.println("Cleanup complete"));
                })
                .block(); // block only in main() — never in reactive production code

        // Clean up temp file
        Files.deleteIfExists(tempFile);
    }
}
