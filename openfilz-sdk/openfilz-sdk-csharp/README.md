# OpenFilz C# SDK

C# / .NET client library for the OpenFilz Document Management REST API.

## Code Samples

The [`samples/`](samples/) directory contains runnable C# samples:

- **`Quickstart.cs`** — Complete workflow demonstrating all core operations (folder CRUD, file upload/download/rename/move/copy/delete, favorites, metadata, dashboard, audit trail)
- **`QuickstartTest.cs`** — xUnit integration test that validates the samples against a running API

To run the sample tests locally (requires a running OpenFilz API):

```bash
cd samples
OPENFILZ_API_URL=http://localhost:8081 dotnet test
```

These samples are automatically tested in CI.

## Installation

```bash
dotnet add package OpenFilz.Sdk
```

Or via NuGet Package Manager:

```
Install-Package OpenFilz.Sdk
```

## Quick Start

The C# SDK uses the Microsoft Generic Host pattern with dependency injection.
All API methods return `IXxxApiResponse` wrappers — use `.Ok()` to access the response data.

### Configuration

```csharp
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Client;
using OpenFilz.Sdk.Extensions;

var host = Host.CreateDefaultBuilder()
    .ConfigureApi((context, services, options) =>
    {
        // For authenticated instances:
        // options.AddTokens(new BearerToken("your-oauth2-access-token"));

        options.AddApiHttpClients(client =>
        {
            client.BaseAddress = new Uri("https://your-openfilz-instance.com");
        });
    })
    .Build();

// Resolve API instances from the DI container
var documentApi = host.Services.GetRequiredService<IDocumentControllerApi>();
var folderApi = host.Services.GetRequiredService<IFolderControllerApi>();
var fileApi = host.Services.GetRequiredService<IFileControllerApi>();
```

### Upload a Document

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var documentApi = host.Services.GetRequiredService<IDocumentControllerApi>();

using var fileStream = File.OpenRead("/path/to/report.pdf");

var response = await documentApi.UploadDocument1Async(
    file: fileStream,
    parentFolderId: null,           // root folder
    allowDuplicateFileNames: true
);
var uploaded = response.Ok()!;

Console.WriteLine($"Uploaded: {uploaded.Id} - {uploaded.Name}");
```

### Create a Folder

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var folderApi = host.Services.GetRequiredService<IFolderControllerApi>();

var response = await folderApi.CreateFolderAsync(new CreateFolderRequest(
    name: "My Project Documents"
));
var folder = response.Ok()!;

Console.WriteLine($"Created folder: {folder.Id}");
```

### List Folder Contents

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var folderApi = host.Services.GetRequiredService<IFolderControllerApi>();

var response = await folderApi.ListFolderAsync(
    folderId: folderId,
    onlyFiles: false,
    onlyFolders: false
);
var contents = response.Ok()!;

foreach (var item in contents)
{
    Console.WriteLine($"{item.Type}: {item.Name}");
}
```

### Download a Document

```csharp
using OpenFilz.Sdk.Api;

var documentApi = host.Services.GetRequiredService<IDocumentControllerApi>();

var response = await documentApi.DownloadDocumentAsync(documentId);
Stream fileStream = response.Ok()!;

using var outputFile = File.Create("/desired/path/document.pdf");
await fileStream.CopyToAsync(outputFile);
```

### Move Files

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var fileApi = host.Services.GetRequiredService<IFileControllerApi>();

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

var fileApi = host.Services.GetRequiredService<IFileControllerApi>();

var response = await fileApi.CopyFilesAsync(new CopyRequest(
    documentIds: new List<Guid> { fileId },
    targetFolderId: targetFolderId
));
var copies = response.Ok()!;

foreach (var copy in copies)
{
    Console.WriteLine($"Original: {copy.OriginalId} -> Copy: {copy.CopyId}");
}
```

### Rename a File

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var fileApi = host.Services.GetRequiredService<IFileControllerApi>();

var response = await fileApi.RenameFileAsync(fileId, new RenameRequest(
    newName: "renamed-document.pdf"
));
var renamed = response.Ok()!;
Console.WriteLine($"Renamed to: {renamed.Name}");
```

### Delete Files

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var fileApi = host.Services.GetRequiredService<IFileControllerApi>();

await fileApi.DeleteFilesAsync(new DeleteRequest(
    documentIds: new List<Guid> { fileId1, fileId2 }
));
```

### Manage Favorites

```csharp
using OpenFilz.Sdk.Api;

var favApi = host.Services.GetRequiredService<IFavoritesApi>();

// Toggle favorite
var toggleResp = await favApi.ToggleFavoriteAsync(documentId);
bool isFavorite = toggleResp.Ok()!.Value;

// Check status
var statusResp = await favApi.IsFavoriteAsync(documentId);
bool status = statusResp.Ok()!.Value;
```

### Get Document Info with Metadata

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var documentApi = host.Services.GetRequiredService<IDocumentControllerApi>();

var response = await documentApi.GetDocumentInfoAsync(documentId, withMetadata: true);
var info = response.Ok()!;
Console.WriteLine($"Name: {info.Name}");
Console.WriteLine($"Size: {info.Size}");
Console.WriteLine($"Content-Type: {info.ContentType}");
Console.WriteLine($"Metadata: {info.Metadata}");
```

### Update Document Metadata

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var documentApi = host.Services.GetRequiredService<IDocumentControllerApi>();

await documentApi.UpdateDocumentMetadataAsync(documentId, new UpdateMetadataRequest(
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

var dashboardApi = host.Services.GetRequiredService<IDashboardApi>();

var response = await dashboardApi.GetDashboardStatisticsAsync();
var stats = response.Ok()!;
Console.WriteLine($"Total files: {stats.TotalFiles}");
Console.WriteLine($"Total folders: {stats.TotalFolders}");
```

### Audit Trail

```csharp
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Model;

var auditApi = host.Services.GetRequiredService<IAuditControllerApi>();

var response = await auditApi.GetAuditTrailAsync(documentId, sort: "DESC");
var trail = response.Ok()!;

foreach (var entry in trail)
{
    Console.WriteLine($"{entry.Action} by {entry.Username} at {entry.Timestamp}");
}
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
- Microsoft.Extensions.Hosting (included by generated client)
