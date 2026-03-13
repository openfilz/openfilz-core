# OpenFilz TypeScript SDK

TypeScript/JavaScript client library for the OpenFilz Document Management REST API.

## Code Samples

The [`samples/`](samples/) directory contains runnable TypeScript samples:

- **`quickstart.ts`** — Complete workflow demonstrating all core operations (folder CRUD, file upload/download/rename/move/copy/delete, favorites, metadata, dashboard, audit trail)
- **`quickstart.test.ts`** — Jest integration test that validates the samples against a running API

To run the sample tests locally (requires a running OpenFilz API):

```bash
cd samples
npm install
OPENFILZ_API_URL=http://localhost:8081 npm test
```

These samples are automatically tested in CI.

## Installation

```bash
npm install @openfilz-sdk/typescript
```

## Quick Start

### Configuration

```typescript
import { Configuration, DocumentControllerApi, FolderControllerApi } from '@openfilz-sdk/typescript';

const config = new Configuration({
  basePath: 'https://your-openfilz-instance.com',
  accessToken: 'your-oauth2-access-token',
});
```

### Upload a Document

```typescript
import { DocumentControllerApi } from '@openfilz-sdk/typescript';

const documentApi = new DocumentControllerApi(config);

const file = new File(['content'], 'report.pdf', { type: 'application/pdf' });

const response = await documentApi.uploadDocument1(
  file,           // file
  true,           // allowDuplicateFileNames
  undefined,      // parentFolderId (root if undefined)
  undefined,      // metadata
);

console.log(`Uploaded: ${response.data.id} - ${response.data.name}`);
```

### Create a Folder

```typescript
import { FolderControllerApi } from '@openfilz-sdk/typescript';

const folderApi = new FolderControllerApi(config);

const folder = await folderApi.createFolder({
  name: 'My Project Documents',
  parentId: undefined, // root folder
});

console.log(`Created folder: ${folder.data.id}`);
```

### List Folder Contents

```typescript
import { FolderControllerApi } from '@openfilz-sdk/typescript';

const folderApi = new FolderControllerApi(config);

const contents = await folderApi.listFolder(
  folderId,   // folder UUID (undefined for root)
  false,      // onlyFiles
  false       // onlyFolders
);

contents.data.forEach(item => {
  console.log(`${item.type}: ${item.name}`);
});
```

### Download a Document

```typescript
import { DocumentControllerApi } from '@openfilz-sdk/typescript';

const documentApi = new DocumentControllerApi(config);

const response = await documentApi.downloadDocument(documentId, {
  responseType: 'blob',
});

// Save or process the blob
const blob = response.data;
```

### Move Files

```typescript
import { FileControllerApi } from '@openfilz-sdk/typescript';

const fileApi = new FileControllerApi(config);

await fileApi.moveFiles({
  documentIds: [fileId1, fileId2],
  targetFolderId: targetFolderId,
  allowDuplicateFileNames: false,
});
```

### Copy Files

```typescript
import { FileControllerApi } from '@openfilz-sdk/typescript';

const fileApi = new FileControllerApi(config);

const copies = await fileApi.copyFiles({
  documentIds: [fileId],
  targetFolderId: targetFolderId,
});

copies.data.forEach(copy => {
  console.log(`Original: ${copy.originalId} -> Copy: ${copy.copyId}`);
});
```

### Rename a File

```typescript
import { FileControllerApi } from '@openfilz-sdk/typescript';

const fileApi = new FileControllerApi(config);

await fileApi.renameFile(fileId, {
  newName: 'renamed-document.pdf',
});
```

### Delete Files

```typescript
import { FileControllerApi } from '@openfilz-sdk/typescript';

const fileApi = new FileControllerApi(config);

await fileApi.deleteFiles({
  documentIds: [fileId1, fileId2],
});
```

### Manage Favorites

```typescript
import { FavoritesApi } from '@openfilz-sdk/typescript';

const favApi = new FavoritesApi(config);

// Toggle favorite
const isFavorite = await favApi.toggleFavorite(documentId);

// Check status
const status = await favApi.isFavorite(documentId);
```

### Get Document Info with Metadata

```typescript
import { DocumentControllerApi } from '@openfilz-sdk/typescript';

const documentApi = new DocumentControllerApi(config);

const info = await documentApi.getDocumentInfo(documentId, true);
console.log(`Name: ${info.data.name}`);
console.log(`Size: ${info.data.size}`);
console.log(`Content-Type: ${info.data.contentType}`);
console.log(`Metadata:`, info.data.metadata);
```

### Update Document Metadata

```typescript
import { DocumentControllerApi } from '@openfilz-sdk/typescript';

const documentApi = new DocumentControllerApi(config);

await documentApi.updateDocumentMetadata(documentId, {
  metadataToUpdate: {
    project: 'Alpha',
    classification: 'confidential',
    version: 2,
  },
});
```

### Dashboard Statistics

```typescript
import { DashboardApi } from '@openfilz-sdk/typescript';

const dashboardApi = new DashboardApi(config);

const stats = await dashboardApi.getDashboardStatistics();
console.log(`Total files: ${stats.data.totalFiles}`);
console.log(`Total folders: ${stats.data.totalFolders}`);
```

### Audit Trail

```typescript
import { AuditControllerApi } from '@openfilz-sdk/typescript';

const auditApi = new AuditControllerApi(config);

const trail = await auditApi.getAuditTrail(documentId, 'DESC');

trail.data.forEach(entry => {
  console.log(`${entry.action} by ${entry.username} at ${entry.timestamp}`);
});
```

## GraphQL Schema

The SDK includes bundled GraphQL schema files in the `graphql/` directory. Use them with any GraphQL client (e.g., Apollo Client, urql):

- `graphql/document.graphqls` - Core document queries (listFolder, count, favorites)
- `graphql/document-search.graphqls` - Full-text search queries

### GraphQL Example with Apollo Client

```typescript
import { ApolloClient, InMemoryCache, gql } from '@apollo/client';

const apolloClient = new ApolloClient({
  uri: 'https://your-openfilz-instance.com/graphql/v1',
  cache: new InMemoryCache(),
  headers: {
    Authorization: `Bearer ${accessToken}`,
  },
});

const LIST_FOLDER = gql`
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
`;

const { data } = await apolloClient.query({
  query: LIST_FOLDER,
  variables: {
    request: {
      id: folderId,
      pageInfo: {
        pageNumber: 0,
        pageSize: 20,
        sortBy: 'name',
        sortOrder: 'ASC',
      },
    },
  },
});

console.log(data.listFolder);
```

### Full-Text Search Example

```typescript
const SEARCH_DOCUMENTS = gql`
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
  }
`;

const { data } = await apolloClient.query({
  query: SEARCH_DOCUMENTS,
  variables: { query: 'quarterly report', page: 0, size: 10 },
});

console.log(`Found ${data.searchDocuments.totalHits} documents`);
data.searchDocuments.documents.forEach(doc => {
  console.log(`${doc.name}: ${doc.contentSnippet}`);
});
```

## Requirements

- Node.js 18+
- axios (peer dependency)
