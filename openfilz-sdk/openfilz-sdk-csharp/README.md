# OpenFilz C# SDK

C# / .NET client library for the OpenFilz Document Management REST API.

## Installation

```bash
dotnet add package OpenFilz.Sdk
```

Or via NuGet Package Manager:

```
Install-Package OpenFilz.Sdk
```

## Quick Start

### Configuration

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Client;

var config = new Configuration
{
    BasePath = "https://your-openfilz-instance.com"
};
config.AccessToken = "your-oauth2-access-token";
```

### Upload a Document

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var documentApi = new DocumentControllerApi(config);

using var fileStream = File.OpenRead("/path/to/report.pdf");

UploadResponse response = await documentApi.UploadDocumentAsync(
    file: fileStream,
    parentFolderId: null,           // root folder
    metadata: null,
    allowDuplicateFileNames: true
);

Console.WriteLine($"Uploaded: {response.Id} - {response.Name}");
```

### Create a Folder

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var folderApi = new FolderControllerApi(config);

var folder = await folderApi.CreateFolderAsync(new CreateFolderRequest(
    name: "My Project Documents",
    parentId: null  // root folder
));

Console.WriteLine($"Created folder: {folder.Id}");
```

### List Folder Contents

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var folderApi = new FolderControllerApi(config);

List<FolderElementInfo> contents = await folderApi.ListFolderAsync(
    folderId: folderId,  // null for root
    onlyFiles: false,
    onlyFolders: false
);

foreach (var item in contents)
{
    Console.WriteLine($"{item.Type}: {item.Name}");
}
```

### Download a Document

```csharp
using OpenFilz.Sdk.Api;

var documentApi = new DocumentControllerApi(config);

Stream fileStream = await documentApi.DownloadDocumentAsync(documentId);

using var outputFile = File.Create("/desired/path/document.pdf");
await fileStream.CopyToAsync(outputFile);
```

### Move Files

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var fileApi = new FileControllerApi(config);

await fileApi.MoveFilesAsync(new MoveRequest(
    documentIds: new List<Guid> { fileId1, fileId2 },
    targetFolderId: targetFolderId,
    allowDuplicateFileNames: false
));
```

### Copy Files

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var fileApi = new FileControllerApi(config);

List<CopyResponse> copies = await fileApi.CopyFilesAsync(new CopyRequest(
    documentIds: new List<Guid> { fileId },
    targetFolderId: targetFolderId
));

foreach (var copy in copies)
{
    Console.WriteLine($"Original: {copy.OriginalId} -> Copy: {copy.CopyId}");
}
```

### Rename a File

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var fileApi = new FileControllerApi(config);

await fileApi.RenameFileAsync(fileId, new RenameRequest(
    newName: "renamed-document.pdf"
));
```

### Delete Files

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var fileApi = new FileControllerApi(config);

await fileApi.DeleteFilesAsync(new DeleteRequest(
    documentIds: new List<Guid> { fileId1, fileId2 }
));
```

### Manage Favorites

```csharp
using OpenFilz.Sdk.Api;

var favApi = new FavoriteControllerApi(config);

// Toggle favorite
bool isFavorite = await favApi.ToggleFavoriteAsync(documentId);

// Check status
bool status = await favApi.IsFavoriteAsync(documentId);
```

### Get Document Info with Metadata

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var documentApi = new DocumentControllerApi(config);

DocumentInfo info = await documentApi.GetDocumentInfoAsync(documentId, withMetadata: true);
Console.WriteLine($"Name: {info.Name}");
Console.WriteLine($"Size: {info.Size}");
Console.WriteLine($"Content-Type: {info.ContentType}");
Console.WriteLine($"Metadata: {info.Metadata}");
```

### Update Document Metadata

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var documentApi = new DocumentControllerApi(config);

await documentApi.UpdateMetadataAsync(documentId, new UpdateMetadataRequest(
    metadataToUpdate: new Dictionary<string, object>
    {
        { "project", "Alpha" },
        { "classification", "confidential" },
        { "version", 2 }
    }
));
```

### Dashboard Statistics

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var dashboardApi = new DashboardControllerApi(config);

DashboardStatisticsResponse stats = await dashboardApi.GetStatisticsAsync();
Console.WriteLine($"Total files: {stats.TotalFiles}");
Console.WriteLine($"Total folders: {stats.TotalFolders}");
Console.WriteLine($"Storage used: {stats.TotalStorageUsed} bytes");
```

### Audit Trail

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var auditApi = new AuditControllerApi(config);

List<AuditLog> trail = await auditApi.GetAuditTrailAsync(documentId, sort: "DESC");

foreach (var entry in trail)
{
    Console.WriteLine($"{entry.Action} by {entry.Username} at {entry.Timestamp}");
}
```

### Recycle Bin (Soft Delete)

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var recycleBin = new RecycleBinControllerApi(config);

// List deleted items
List<FolderElementInfo> deleted = await recycleBin.ListDeletedItemsAsync();

// Restore items
await recycleBin.RestoreItemsAsync(new DeleteRequest(
    documentIds: new List<Guid> { deletedItemId }
));

// Empty recycle bin
await recycleBin.EmptyRecycleBinAsync();
```

## GraphQL Schema

The SDK includes bundled GraphQL schema files in the `graphql/` directory:

- `document.graphqls` - Core document queries (listFolder, count, favorites)
- `document-search.graphqls` - Full-text search queries

### GraphQL Example with GraphQL.Client

```csharp
using GraphQL;
using GraphQL.Client.Http;
using GraphQL.Client.Serializer.Newtonsoft;

var graphQlClient = new GraphQLHttpClient(
    "https://your-openfilz-instance.com/graphql/v1",
    new NewtonsoftJsonSerializer()
);
graphQlClient.HttpClient.DefaultRequestHeaders.Authorization =
    new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", accessToken);

var listFolderQuery = new GraphQLRequest
{
    Query = @"
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
        }",
    Variables = new
    {
        request = new
        {
            id = folderId,
            pageInfo = new
            {
                pageNumber = 0,
                pageSize = 20,
                sortBy = "name",
                sortOrder = "ASC"
            }
        }
    }
};

var response = await graphQlClient.SendQueryAsync<dynamic>(listFolderQuery);
foreach (var item in response.Data.listFolder)
{
    Console.WriteLine($"{item.type}: {item.name}");
}
```

### Full-Text Search Example

```csharp
var searchQuery = new GraphQLRequest
{
    Query = @"
        query SearchDocuments($query: String, $page: Int, $size: Int) {
            searchDocuments(query: $query, page: $page, size: $size) {
                totalHits
                documents {
                    id
                    name
                    contentType
                    size
                    contentSnippet
                    createdAt
                }
            }
        }",
    Variables = new
    {
        query = "quarterly report",
        page = 0,
        size = 10
    }
};

var result = await graphQlClient.SendQueryAsync<dynamic>(searchQuery);
Console.WriteLine($"Found {result.Data.searchDocuments.totalHits} documents");
foreach (var doc in result.Data.searchDocuments.documents)
{
    Console.WriteLine($"{doc.name}: {doc.contentSnippet}");
}
```

## Requirements

- .NET 8.0+
- Newtonsoft.Json (included by generated client)
