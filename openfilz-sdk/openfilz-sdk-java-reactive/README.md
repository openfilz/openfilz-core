# OpenFilz Java Reactive SDK

Non-blocking, reactive Java client library for the OpenFilz Document Management REST API, built on Spring WebClient and Project Reactor.

## Blocking vs Reactive — Which SDK to Choose?

Two Java SDKs are available. They expose the **same API operations** but differ in programming model:

| | `openfilz-sdk-java` | `openfilz-sdk-java-reactive` (this one) |
|---|---|---|
| **Style** | Blocking / synchronous | Non-blocking / reactive |
| **HTTP client** | OkHttp 4 | Spring WebClient |
| **Serialization** | Gson | Jackson |
| **Return types** | `T`, `List<T>` | `Mono<T>`, `Flux<T>` |
| **Package** | `org.openfilz.sdk.*` | `org.openfilz.sdk.reactive.*` |
| **Dependencies** | OkHttp + Gson (lightweight, ~2 MB) | Spring WebFlux + Reactor (~15 MB) |
| **Thread model** | Blocks the calling thread until response arrives | Never blocks; uses event loop |
| **Error handling** | try/catch `ApiException` | `.onErrorResume()`, `.doOnError()` |
| **Best for** | Android, plain Java, Spring MVC, scripts, CLI tools | Spring WebFlux, high-concurrency microservices |
| **Min Java** | 17 | 17 |
| **Spring required?** | No | Yes (Spring Boot 3+ / WebFlux 6+) |

**Quick rule:** If your project already uses Spring WebFlux or Project Reactor, pick this SDK. Otherwise, pick `openfilz-sdk-java`.

### Side-by-side example — List folder contents

**Blocking (`openfilz-sdk-java`):**
```java
List<FolderElementInfo> items = folderApi.listFolder(folderId, false, false);
items.forEach(item -> System.out.println(item.getName()));
```

**Reactive (this SDK):**
```java
folderApi.listFolder(folderId, false, false)
    .subscribe(item -> System.out.println(item.getName()));
```

## Installation

### Maven

```xml
<dependency>
    <groupId>org.openfilz</groupId>
    <artifactId>openfilz-sdk-java-reactive</artifactId>
    <version>1.1.5</version>
</dependency>
```

### Gradle

```groovy
implementation 'org.openfilz:openfilz-sdk-java-reactive:1.1.5'
```

## Quick Start

### Configuration

```java
import org.openfilz.sdk.reactive.ApiClient;
import org.springframework.web.reactive.function.client.WebClient;

ApiClient apiClient = new ApiClient(
    WebClient.builder()
        .baseUrl("https://your-openfilz-instance.com")
        .defaultHeader("Authorization", "Bearer " + accessToken)
        .build()
);
```

### Upload a Document

```java
import org.openfilz.sdk.reactive.api.DocumentControllerApi;
import org.openfilz.sdk.reactive.model.UploadResponse;
import reactor.core.publisher.Mono;

DocumentControllerApi documentApi = new DocumentControllerApi(apiClient);

File file = new File("/path/to/document.pdf");

Mono<UploadResponse> response = documentApi.uploadDocument(
    file,           // file
    null,           // parentFolderId (root if null)
    null,           // metadata
    true            // allowDuplicateFileNames
);

response.subscribe(uploaded ->
    System.out.println("Uploaded: " + uploaded.getId() + " - " + uploaded.getName())
);
```

### Create a Folder

```java
import org.openfilz.sdk.reactive.api.FolderControllerApi;
import org.openfilz.sdk.reactive.model.CreateFolderRequest;
import org.openfilz.sdk.reactive.model.FolderResponse;

FolderControllerApi folderApi = new FolderControllerApi(apiClient);

CreateFolderRequest request = new CreateFolderRequest();
request.setName("My Project Documents");
request.setParentId(null);

Mono<FolderResponse> folder = folderApi.createFolder(request);

folder.subscribe(f -> System.out.println("Created folder: " + f.getId()));
```

### List Folder Contents

```java
import org.openfilz.sdk.reactive.api.FolderControllerApi;
import org.openfilz.sdk.reactive.model.FolderElementInfo;
import reactor.core.publisher.Flux;

FolderControllerApi folderApi = new FolderControllerApi(apiClient);

Flux<FolderElementInfo> contents = folderApi.listFolder(folderId, false, false);

contents.subscribe(item ->
    System.out.println(item.getType() + ": " + item.getName())
);
```

### Download a Document

```java
import org.openfilz.sdk.reactive.api.DocumentControllerApi;
import org.springframework.core.io.Resource;

DocumentControllerApi documentApi = new DocumentControllerApi(apiClient);

Mono<Resource> resource = documentApi.downloadDocument(documentId);

resource.flatMap(res -> {
    // Process the reactive Resource (e.g., write to file)
    return DataBufferUtils.write(
        res.getReadableChannel(),
        Path.of("/desired/path/document.pdf")
    );
}).subscribe();
```

### Reactive Composition — Upload then Move

```java
DocumentControllerApi documentApi = new DocumentControllerApi(apiClient);
FileControllerApi fileApi = new FileControllerApi(apiClient);

documentApi.uploadDocument(file, null, null, true)
    .flatMap(uploaded -> {
        MoveRequest moveRequest = new MoveRequest();
        moveRequest.setDocumentIds(List.of(uploaded.getId()));
        moveRequest.setTargetFolderId(targetFolderId);
        return fileApi.moveFiles(moveRequest)
            .thenReturn(uploaded);
    })
    .subscribe(uploaded ->
        System.out.println("Uploaded and moved: " + uploaded.getName())
    );
```

### Move Files

```java
import org.openfilz.sdk.reactive.api.FileControllerApi;
import org.openfilz.sdk.reactive.model.MoveRequest;

FileControllerApi fileApi = new FileControllerApi(apiClient);

MoveRequest moveRequest = new MoveRequest();
moveRequest.setDocumentIds(List.of(fileId1, fileId2));
moveRequest.setTargetFolderId(targetFolderId);
moveRequest.setAllowDuplicateFileNames(false);

fileApi.moveFiles(moveRequest).subscribe();
```

### Copy Files

```java
import org.openfilz.sdk.reactive.api.FileControllerApi;
import org.openfilz.sdk.reactive.model.CopyRequest;
import org.openfilz.sdk.reactive.model.CopyResponse;

FileControllerApi fileApi = new FileControllerApi(apiClient);

CopyRequest copyRequest = new CopyRequest();
copyRequest.setDocumentIds(List.of(fileId));
copyRequest.setTargetFolderId(targetFolderId);

Flux<CopyResponse> copies = fileApi.copyFiles(copyRequest);

copies.subscribe(copy ->
    System.out.println("Original: " + copy.getOriginalId() + " -> Copy: " + copy.getCopyId())
);
```

### Rename a File

```java
import org.openfilz.sdk.reactive.api.FileControllerApi;
import org.openfilz.sdk.reactive.model.RenameRequest;

FileControllerApi fileApi = new FileControllerApi(apiClient);

RenameRequest rename = new RenameRequest();
rename.setNewName("renamed-document.pdf");

fileApi.renameFile(fileId, rename).subscribe();
```

### Delete Files

```java
import org.openfilz.sdk.reactive.api.FileControllerApi;
import org.openfilz.sdk.reactive.model.DeleteRequest;

FileControllerApi fileApi = new FileControllerApi(apiClient);

DeleteRequest deleteRequest = new DeleteRequest();
deleteRequest.setDocumentIds(List.of(fileId1, fileId2));

fileApi.deleteFiles(deleteRequest).subscribe();
```

### Manage Favorites

```java
import org.openfilz.sdk.reactive.api.FavoriteControllerApi;

FavoriteControllerApi favApi = new FavoriteControllerApi(apiClient);

// Toggle favorite
Mono<Boolean> isFavorite = favApi.toggleFavorite(documentId);

// Check status
Mono<Boolean> status = favApi.isFavorite(documentId);
```

### Get Document Info with Metadata

```java
import org.openfilz.sdk.reactive.api.DocumentControllerApi;
import org.openfilz.sdk.reactive.model.DocumentInfo;

DocumentControllerApi documentApi = new DocumentControllerApi(apiClient);

documentApi.getDocumentInfo(documentId, true)
    .subscribe(info -> {
        System.out.println("Name: " + info.getName());
        System.out.println("Size: " + info.getSize());
        System.out.println("Content-Type: " + info.getContentType());
        System.out.println("Metadata: " + info.getMetadata());
    });
```

### Update Document Metadata

```java
import org.openfilz.sdk.reactive.api.DocumentControllerApi;
import org.openfilz.sdk.reactive.model.UpdateMetadataRequest;

DocumentControllerApi documentApi = new DocumentControllerApi(apiClient);

UpdateMetadataRequest metadata = new UpdateMetadataRequest();
metadata.setMetadataToUpdate(Map.of(
    "project", "Alpha",
    "classification", "confidential",
    "version", 2
));

documentApi.updateMetadata(documentId, metadata).subscribe();
```

### Dashboard Statistics

```java
import org.openfilz.sdk.reactive.api.DashboardControllerApi;
import org.openfilz.sdk.reactive.model.DashboardStatisticsResponse;

DashboardControllerApi dashboardApi = new DashboardControllerApi(apiClient);

dashboardApi.getStatistics()
    .subscribe(stats -> {
        System.out.println("Total files: " + stats.getTotalFiles());
        System.out.println("Total folders: " + stats.getTotalFolders());
        System.out.println("Storage used: " + stats.getTotalStorageUsed() + " bytes");
    });
```

### Audit Trail

```java
import org.openfilz.sdk.reactive.api.AuditControllerApi;
import org.openfilz.sdk.reactive.model.AuditLog;

AuditControllerApi auditApi = new AuditControllerApi(apiClient);

Flux<AuditLog> trail = auditApi.getAuditTrail(documentId, "DESC");

trail.subscribe(entry ->
    System.out.println(entry.getAction() + " by " + entry.getUsername()
        + " at " + entry.getTimestamp())
);
```

### Recycle Bin (Soft Delete)

```java
import org.openfilz.sdk.reactive.api.RecycleBinControllerApi;
import org.openfilz.sdk.reactive.model.DeleteRequest;

RecycleBinControllerApi recycleBin = new RecycleBinControllerApi(apiClient);

// List deleted items
Flux<FolderElementInfo> deleted = recycleBin.listDeletedItems();

// Restore items
DeleteRequest restoreRequest = new DeleteRequest();
restoreRequest.setDocumentIds(List.of(deletedItemId));
recycleBin.restoreItems(restoreRequest).subscribe();

// Empty recycle bin
recycleBin.emptyRecycleBin().subscribe();
```

### Using in a Spring WebFlux Controller

```java
@RestController
@RequestMapping("/my-app/documents")
public class MyDocumentController {

    private final DocumentControllerApi openfilzApi;

    public MyDocumentController(ApiClient apiClient) {
        this.openfilzApi = new DocumentControllerApi(apiClient);
    }

    @GetMapping("/{id}/info")
    public Mono<DocumentInfo> getDocumentInfo(@PathVariable UUID id) {
        return openfilzApi.getDocumentInfo(id, true);
    }

    @GetMapping("/{id}/download")
    public Mono<Resource> downloadDocument(@PathVariable UUID id) {
        return openfilzApi.downloadDocument(id);
    }
}
```

## GraphQL Schema

The SDK includes bundled GraphQL schema files under the `graphql/` resource directory:

- `graphql/document.graphqls` - Core document queries (listFolder, count, favorites)
- `graphql/document-search.graphqls` - Full-text search queries

### GraphQL Example with Spring GraphQL (Reactive)

```java
HttpGraphQlClient graphQlClient = HttpGraphQlClient.builder(
    WebClient.builder()
        .baseUrl("https://your-openfilz-instance.com/graphql/v1")
        .defaultHeader("Authorization", "Bearer " + token)
        .build()
).build();

// List folder contents with pagination
Mono<List<FolderElementInfo>> items = graphQlClient.document("""
        query ListFolder($request: ListFolderRequest!) {
            listFolder(request: $request) {
                id
                type
                name
                contentType
                size
                createdAt
                updatedAt
                favorite
            }
        }
    """)
    .variable("request", Map.of(
        "id", folderId,
        "pageInfo", Map.of(
            "pageNumber", 0,
            "pageSize", 20,
            "sortBy", "name",
            "sortOrder", "ASC"
        )
    ))
    .retrieve("listFolder")
    .toEntityList(FolderElementInfo.class);

items.subscribe(list ->
    list.forEach(item -> System.out.println(item.getType() + ": " + item.getName()))
);
```

## Requirements

- Java 17+
- Spring WebFlux 6+ / Spring Boot 3+
- Project Reactor
