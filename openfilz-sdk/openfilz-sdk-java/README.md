# OpenFilz Java SDK

Blocking Java client library for the OpenFilz Document Management REST API, built on OkHttp and Gson.

## Blocking vs Reactive — Which SDK to Choose?

Two Java SDKs are available. They expose the **same API operations** but differ in programming model:

| | `openfilz-sdk-java` (this one) | `openfilz-sdk-java-reactive` |
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
| **Min Java** | 25 | 25 |
| **Spring required?** | No | Yes (Spring Boot 3+ / WebFlux 6+) |

**Quick rule:** If your project already uses Spring WebFlux or Project Reactor, pick `openfilz-sdk-java-reactive`. Otherwise, pick this one.

### Side-by-side example — List folder contents

**Blocking (this SDK):**
```java
List<FolderElementInfo> items = folderApi.listFolder(folderId, false, false);
items.forEach(item -> System.out.println(item.getName()));
```

**Reactive (`openfilz-sdk-java-reactive`):**
```java
folderApi.listFolder(folderId, false, false)
    .subscribe(item -> System.out.println(item.getName()));
```

## Installation

### Maven

```xml
<dependency>
    <groupId>org.openfilz</groupId>
    <artifactId>openfilz-sdk-java</artifactId>
    <version>1.1.5</version>
</dependency>
```

### Gradle

```groovy
implementation 'org.openfilz:openfilz-sdk-java:1.1.5'
```

## Quick Start

### Configuration

```java
import org.openfilz.sdk.ApiClient;
import org.openfilz.sdk.Configuration;
import org.openfilz.sdk.auth.HttpBearerAuth;

ApiClient client = Configuration.getDefaultApiClient();
client.setBasePath("https://your-openfilz-instance.com");

// Configure OAuth2 Bearer token
HttpBearerAuth bearerAuth = (HttpBearerAuth) client.getAuthentication("keycloak_auth");
bearerAuth.setBearerToken("your-access-token");
```

### Upload a Document

```java
import org.openfilz.sdk.api.DocumentControllerApi;
import org.openfilz.sdk.model.UploadResponse;

DocumentControllerApi documentApi = new DocumentControllerApi(client);

File file = new File("/path/to/document.pdf");
UploadResponse response = documentApi.uploadDocument(
    file,           // file
    null,           // parentFolderId (root if null)
    null,           // metadata (optional JSON)
    true            // allowDuplicateFileNames
);

System.out.println("Uploaded: " + response.getId() + " - " + response.getName());
```

### Create a Folder

```java
import org.openfilz.sdk.api.FolderControllerApi;
import org.openfilz.sdk.model.CreateFolderRequest;
import org.openfilz.sdk.model.FolderResponse;

FolderControllerApi folderApi = new FolderControllerApi(client);

CreateFolderRequest request = new CreateFolderRequest();
request.setName("My Project Documents");
request.setParentId(null); // root folder

FolderResponse folder = folderApi.createFolder(request);
System.out.println("Created folder: " + folder.getId());
```

### List Folder Contents

```java
import org.openfilz.sdk.api.FolderControllerApi;
import org.openfilz.sdk.model.FolderElementInfo;

FolderControllerApi folderApi = new FolderControllerApi(client);

List<FolderElementInfo> contents = folderApi.listFolder(
    folderId,   // folder UUID (null for root)
    false,      // onlyFiles
    false       // onlyFolders
);

for (FolderElementInfo item : contents) {
    System.out.println(item.getType() + ": " + item.getName());
}
```

### Download a Document

```java
import org.openfilz.sdk.api.DocumentControllerApi;

DocumentControllerApi documentApi = new DocumentControllerApi(client);

File downloaded = documentApi.downloadDocument(documentId);
// File is saved to a temp location, move it as needed
Files.copy(downloaded.toPath(), Path.of("/desired/path/document.pdf"));
```

### Move Files

```java
import org.openfilz.sdk.api.FileControllerApi;
import org.openfilz.sdk.model.MoveRequest;

FileControllerApi fileApi = new FileControllerApi(client);

MoveRequest moveRequest = new MoveRequest();
moveRequest.setDocumentIds(List.of(fileId1, fileId2));
moveRequest.setTargetFolderId(targetFolderId);
moveRequest.setAllowDuplicateFileNames(false);

fileApi.moveFiles(moveRequest);
```

### Copy Files

```java
import org.openfilz.sdk.api.FileControllerApi;
import org.openfilz.sdk.model.CopyRequest;
import org.openfilz.sdk.model.CopyResponse;

FileControllerApi fileApi = new FileControllerApi(client);

CopyRequest copyRequest = new CopyRequest();
copyRequest.setDocumentIds(List.of(fileId));
copyRequest.setTargetFolderId(targetFolderId);

List<CopyResponse> copies = fileApi.copyFiles(copyRequest);
for (CopyResponse copy : copies) {
    System.out.println("Original: " + copy.getOriginalId() + " -> Copy: " + copy.getCopyId());
}
```

### Rename a File

```java
import org.openfilz.sdk.api.FileControllerApi;
import org.openfilz.sdk.model.RenameRequest;

FileControllerApi fileApi = new FileControllerApi(client);

RenameRequest rename = new RenameRequest();
rename.setNewName("renamed-document.pdf");

fileApi.renameFile(fileId, rename);
```

### Delete Files

```java
import org.openfilz.sdk.api.FileControllerApi;
import org.openfilz.sdk.model.DeleteRequest;

FileControllerApi fileApi = new FileControllerApi(client);

DeleteRequest deleteRequest = new DeleteRequest();
deleteRequest.setDocumentIds(List.of(fileId1, fileId2));

fileApi.deleteFiles(deleteRequest);
```

### Manage Favorites

```java
import org.openfilz.sdk.api.FavoriteControllerApi;

FavoriteControllerApi favApi = new FavoriteControllerApi(client);

// Toggle favorite
boolean isFavorite = favApi.toggleFavorite(documentId);

// Check status
boolean status = favApi.isFavorite(documentId);
```

### Get Document Info with Metadata

```java
import org.openfilz.sdk.api.DocumentControllerApi;
import org.openfilz.sdk.model.DocumentInfo;

DocumentControllerApi documentApi = new DocumentControllerApi(client);

DocumentInfo info = documentApi.getDocumentInfo(documentId, true);
System.out.println("Name: " + info.getName());
System.out.println("Size: " + info.getSize());
System.out.println("Content-Type: " + info.getContentType());
System.out.println("Metadata: " + info.getMetadata());
```

### Update Document Metadata

```java
import org.openfilz.sdk.api.DocumentControllerApi;
import org.openfilz.sdk.model.UpdateMetadataRequest;

DocumentControllerApi documentApi = new DocumentControllerApi(client);

UpdateMetadataRequest metadata = new UpdateMetadataRequest();
metadata.setMetadataToUpdate(Map.of(
    "project", "Alpha",
    "classification", "confidential",
    "version", 2
));

documentApi.updateMetadata(documentId, metadata);
```

### Dashboard Statistics

```java
import org.openfilz.sdk.api.DashboardControllerApi;
import org.openfilz.sdk.model.DashboardStatisticsResponse;

DashboardControllerApi dashboardApi = new DashboardControllerApi(client);

DashboardStatisticsResponse stats = dashboardApi.getStatistics();
System.out.println("Total files: " + stats.getTotalFiles());
System.out.println("Total folders: " + stats.getTotalFolders());
System.out.println("Storage used: " + stats.getTotalStorageUsed() + " bytes");
```

### Audit Trail

```java
import org.openfilz.sdk.api.AuditControllerApi;
import org.openfilz.sdk.model.AuditLog;

AuditControllerApi auditApi = new AuditControllerApi(client);

// Get audit trail for a document
List<AuditLog> trail = auditApi.getAuditTrail(documentId, "DESC");

for (AuditLog entry : trail) {
    System.out.println(entry.getAction() + " by " + entry.getUsername()
        + " at " + entry.getTimestamp());
}
```

### Recycle Bin (Soft Delete)

```java
import org.openfilz.sdk.api.RecycleBinControllerApi;
import org.openfilz.sdk.model.DeleteRequest;

RecycleBinControllerApi recycleBin = new RecycleBinControllerApi(client);

// List deleted items
List<FolderElementInfo> deleted = recycleBin.listDeletedItems();

// Restore items
DeleteRequest restoreRequest = new DeleteRequest();
restoreRequest.setDocumentIds(List.of(deletedItemId));
recycleBin.restoreItems(restoreRequest);

// Empty recycle bin
recycleBin.emptyRecycleBin();
```

## GraphQL Schema

The SDK includes bundled GraphQL schema files under the `graphql/` resource directory. These can be used with any Java GraphQL client library (e.g., Spring GraphQL, Netflix DGS):

- `graphql/document.graphqls` - Core document queries (listFolder, count, favorites)
- `graphql/document-search.graphqls` - Full-text search queries

### GraphQL Example with Spring GraphQL

```java
HttpGraphQlClient graphQlClient = HttpGraphQlClient.builder(
    WebClient.builder()
        .baseUrl("https://your-openfilz-instance.com/graphql/v1")
        .defaultHeader("Authorization", "Bearer " + token)
        .build()
).build();

// List folder contents with pagination
String query = """
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
    """;

Map<String, Object> request = Map.of(
    "id", folderId,
    "pageInfo", Map.of(
        "pageNumber", 0,
        "pageSize", 20,
        "sortBy", "name",
        "sortOrder", "ASC"
    )
);

List<FolderElementInfo> items = graphQlClient.document(query)
    .variable("request", request)
    .retrieveSync("listFolder")
    .toEntityList(FolderElementInfo.class);
```

## Requirements

- Java 25+
- OkHttp 4.12+
- Gson 2.11+
