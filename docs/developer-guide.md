# OpenFilz Developer Guide

This guide is intended for developers who want to **integrate with OpenFilz** — consuming the REST API, GraphQL API, or using an official SDK. For contributing to the OpenFilz codebase itself, see the [Contributor Guide](contributor-guide.md).

---

## Table of Contents

- [Overview](#overview)
- [Authentication](#authentication)
  - [User Tokens (Browser Applications)](#user-tokens-browser-applications)
  - [Service Account Tokens (Server-to-Server)](#service-account-tokens-server-to-server)
- [REST API](#rest-api)
  - [Base URL and Documentation](#base-url-and-documentation)
  - [Endpoints Reference](#endpoints-reference)
  - [Upload Files](#upload-files)
  - [Resumable Uploads (TUS Protocol)](#resumable-uploads-tus-protocol)
  - [Download Files](#download-files)
  - [Folder Operations](#folder-operations)
  - [Metadata](#metadata)
  - [Favorites](#favorites)
  - [Recycle Bin](#recycle-bin)
  - [Audit Trail](#audit-trail)
  - [Dashboard](#dashboard)
  - [AI Chat](#ai-chat)
- [GraphQL API](#graphql-api)
  - [Endpoint and Explorer](#endpoint-and-explorer)
  - [Queries](#queries)
  - [Filtering and Pagination](#filtering-and-pagination)
  - [Custom Scalars](#custom-scalars)
- [Official SDKs](#official-sdks)
  - [Available SDKs](#available-sdks)
  - [Installation](#installation)
  - [Quick Start Examples](#quick-start-examples)
- [Keycloak Service Account Setup](#keycloak-service-account-setup)
- [Error Handling](#error-handling)
- [Rate Limits and Quotas](#rate-limits-and-quotas)

---

## Overview

OpenFilz exposes two complementary APIs:

| API | Protocol | Best For |
|-----|----------|----------|
| **REST** | HTTP/JSON + OpenAPI 3.0 | CRUD operations, file uploads/downloads, admin tasks |
| **GraphQL** | HTTP/JSON | Flexible queries, listing folders, filtering, pagination |

Both APIs are secured with **JWT bearer tokens** issued by Keycloak (OIDC). All requests must include an `Authorization: Bearer <token>` header (unless authentication is disabled for development).

---

## Authentication

### User Tokens (Browser Applications)

For browser-based SPAs, use the standard **OIDC Authorization Code flow with PKCE**:

1. Redirect the user to Keycloak's authorization endpoint
2. Receive an authorization code
3. Exchange for an access token (JWT)
4. Include the token in API requests: `Authorization: Bearer <token>`

The pre-configured Keycloak client `openfilz-web` supports this flow. You can create additional clients for your own applications.

### Service Account Tokens (Server-to-Server)

For backend services, scripts, or automated integrations that call the OpenFilz API without user interaction, create a Keycloak **service account client**:

#### Step 1: Create a Confidential Client

In Keycloak Admin Console (`/admin/master/console/#/openfilz/clients`):

1. **Clients > Create client**
2. **General Settings:**
   - Client ID: `my-service` (choose any name)
   - Client Protocol: `openid-connect`
3. **Capability config:**
   - Client authentication: **ON** (confidential client)
   - Authorization: **OFF**
   - Standard flow: **OFF** (no browser login)
   - Direct access grants: **OFF**
   - Service accounts roles: **ON** (enables `client_credentials` grant)
4. **Save**

#### Step 2: Get the Client Secret

Go to the **Credentials** tab of your new client and copy the **Client secret**.

#### Step 3: Assign Roles

The roles you assign determine what the service account can do:

| Role | Grants Access To |
|------|-----------------|
| `READER` | GET requests, GraphQL queries, downloads, search |
| `CONTRIBUTOR` | Upload, create, rename, move, copy, update metadata |
| `CLEANER` | Delete files and folders, empty recycle bin |
| `AUDITOR` | Audit trail queries and chain verification |

Assign one or more roles depending on your integration needs. A typical read-write integration would assign `READER`, `CONTRIBUTOR`, and `CLEANER`.

**If using `REALM_ACCESS` mode** (default):
1. Go to **Clients > my-service > Service account roles**
2. Click **Assign role** and select the desired realm roles

**If using `GROUPS` mode** (`openfilz.security.role-token-lookup=GROUPS`):
1. Go to **Users** > find the service account user (auto-named `service-account-my-service`)
2. Go to the **Groups** tab
3. Join the appropriate groups (e.g., `/OPENFILZ/READER`, `/OPENFILZ/CONTRIBUTOR`)

#### Step 4: Verify Protocol Mappers

Ensure the client inherits the necessary mappers so that roles or groups appear in the access token. Check **Client scopes > my-service-dedicated**:

- **realm roles** mapper: type `User Realm Role`, token claim name `realm_access.roles`, added to access token
- **groups** mapper (if using GROUPS mode): type `Group Membership`, token claim name `groups`, full group path **ON**

The default `openfilz` realm usually has these mappers already.

#### Step 5: Obtain an Access Token

```bash
curl -X POST \
  "http://<keycloak-host>/realms/openfilz/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=my-service" \
  -d "client_secret=<your-client-secret>"
```

Response:

```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIs...",
  "expires_in": 300,
  "token_type": "Bearer"
}
```

#### Step 6: Call the API

```bash
curl -H "Authorization: Bearer <access_token>" \
  "http://<api-host>/api/v1/folders/list"
```

#### Note: Email Claim

OpenFilz uses the `email` claim from the JWT to identify the user. Service accounts get a synthetic email (e.g., `service-account-my-service@placeholder.org`). If the API scopes documents per user, the service account only sees its own documents. You can set a custom email on the service account user in Keycloak if needed.

---

## REST API

### Base URL and Documentation

| Resource | URL |
|----------|-----|
| Base URL | `http://<host>:8081/api/v1` |
| Swagger UI | `http://<host>:8081/swagger-ui.html` |
| OpenAPI Spec | `http://<host>:8081/v3/api-docs` |

The Swagger UI provides interactive documentation where you can try out every endpoint.

### Endpoints Reference

#### Document Management

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/documents/upload` | Upload a single file |
| `POST` | `/documents/upload-multiple` | Upload multiple files |
| `POST` | `/documents/create-blank` | Create a blank document (Word, Excel, PowerPoint, Text) |
| `GET` | `/documents/{id}` | Get document info |
| `GET` | `/documents/{id}/download` | Download a document |
| `PUT` | `/documents/{id}/replace-content` | Replace file content |
| `PUT` | `/documents/{id}` | Update document |
| `DELETE` | `/documents/{id}` | Delete document(s) |
| `POST` | `/documents/move` | Move documents |
| `POST` | `/documents/copy` | Copy documents |
| `POST` | `/documents/rename` | Rename a document |
| `POST` | `/documents/download-multiple` | Download multiple as ZIP |
| `GET` | `/documents/{id}/ancestors` | Get parent folder chain |
| `GET` | `/documents/{id}/position` | Get position in folder |

#### Folder Management

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/folders` | Create a folder |
| `GET` | `/folders/list` | List folder contents |
| `GET` | `/folders/count` | Count items in folder |
| `POST` | `/folders/move` | Move folders |
| `POST` | `/folders/copy` | Copy folders (recursive) |
| `PUT` | `/folders/{folderId}/rename` | Rename a folder |
| `DELETE` | `/folders` | Delete folders |

#### File Management

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/files/move` | Move files |
| `POST` | `/files/copy` | Copy files |
| `PUT` | `/files/{fileId}/rename` | Rename a file |
| `DELETE` | `/files` | Delete files |

#### Metadata

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/documents/{id}/search/metadata` | Get document metadata |
| `PUT` | `/documents/{id}/metadata` | Update metadata (merge) |
| `DELETE` | `/documents/{id}/metadata` | Delete metadata keys |
| `POST` | `/documents/search/ids-by-metadata` | Find documents by metadata |

#### Favorites

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/favorites/{documentId}` | Add to favorites |
| `DELETE` | `/favorites/{documentId}` | Remove from favorites |
| `PUT` | `/favorites/{documentId}/toggle` | Toggle favorite |
| `GET` | `/favorites/{documentId}/is-favorite` | Check favorite status |

#### Recycle Bin

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/recycle-bin` | List deleted items |
| `GET` | `/recycle-bin/count` | Count deleted items |
| `POST` | `/recycle-bin/restore` | Restore items |
| `DELETE` | `/recycle-bin` | Permanently delete items |
| `DELETE` | `/recycle-bin/empty` | Empty recycle bin |

#### Audit

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/audit/{id}` | Get audit trail for a resource |
| `POST` | `/audit/search` | Search audit logs |
| `GET` | `/audit/verify` | Verify audit chain integrity |

#### Other

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/dashboard/statistics` | Dashboard metrics |
| `POST` | `/ai/chat` | Send a message and receive a streaming AI response (SSE) |
| `GET` | `/ai/conversations` | List all AI chat conversations |
| `GET` | `/ai/conversations/{id}` | Get conversation message history |
| `DELETE` | `/ai/conversations/{id}` | Delete a conversation and all its messages |
| `GET` | `/settings` | User settings and quotas |
| `POST` | `/suggestions/search` | Document name suggestions |
| `GET` | `/thumbnails/img/{documentId}` | Get document thumbnail |

### Upload Files

#### Single File Upload

```bash
curl -X POST "http://localhost:8081/api/v1/documents/upload" \
  -H "Authorization: Bearer <token>" \
  -F "file=@document.pdf" \
  -F "parentId=<folder-uuid>" \
  -F "metadata={\"project\":\"alpha\",\"department\":\"engineering\"}"
```

#### Multiple File Upload

```bash
curl -X POST "http://localhost:8081/api/v1/documents/upload-multiple" \
  -H "Authorization: Bearer <token>" \
  -F "files=@file1.pdf" \
  -F "files=@file2.docx" \
  -F "parentId=<folder-uuid>"
```

Multi-file uploads return:
- **200** — all files uploaded successfully
- **207 Multi-Status** — some files succeeded, some failed (partial result)

#### Create Blank Document

```bash
curl -X POST "http://localhost:8081/api/v1/documents/create-blank" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"parentId": "<folder-uuid>", "name": "Report.docx", "templateType": "WORD"}'
```

Template types: `WORD`, `EXCEL`, `POWERPOINT`, `TEXT`.

### Resumable Uploads (TUS Protocol)

For large files (>100 MB), use the TUS protocol for resumable, chunked uploads:

```bash
# 1. Create upload session
curl -X POST "http://localhost:8081/api/v1/tus" \
  -H "Authorization: Bearer <token>" \
  -H "Upload-Length: 524288000" \
  -H "Upload-Metadata: filename dG90YWwucGRm,parentId <base64-encoded-uuid>"

# Returns: Location: /api/v1/tus/<uploadId>

# 2. Upload chunks
curl -X PATCH "http://localhost:8081/api/v1/tus/<uploadId>" \
  -H "Authorization: Bearer <token>" \
  -H "Upload-Offset: 0" \
  -H "Content-Type: application/offset+octet-stream" \
  --data-binary @chunk1.bin

# 3. Check progress
curl -I "http://localhost:8081/api/v1/tus/<uploadId>" \
  -H "Authorization: Bearer <token>"
# Returns: Upload-Offset: <bytes-received>

# 4. Finalize
curl -X POST "http://localhost:8081/api/v1/tus/<uploadId>/finalize" \
  -H "Authorization: Bearer <token>"
```

### Download Files

```bash
# Single file
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8081/api/v1/documents/<id>/download" -o file.pdf

# Multiple files as ZIP
curl -X POST "http://localhost:8081/api/v1/documents/download-multiple" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"documentIds": ["<id1>", "<id2>"]}' -o files.zip
```

### Folder Operations

```bash
# Create folder
curl -X POST "http://localhost:8081/api/v1/folders" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"name": "My Folder", "parentId": null}'

# List folder contents (root)
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8081/api/v1/folders/list"

# List specific folder
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8081/api/v1/folders/list?parentId=<folder-uuid>"
```

### Metadata

```bash
# Get metadata
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8081/api/v1/documents/<id>/search/metadata"

# Update metadata (merge — adds/updates keys without removing existing ones)
curl -X PUT "http://localhost:8081/api/v1/documents/<id>/metadata" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"project": "beta", "status": "approved"}'

# Delete specific metadata keys
curl -X DELETE "http://localhost:8081/api/v1/documents/<id>/metadata" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '["status", "temporary_key"]'

# Search by metadata
curl -X POST "http://localhost:8081/api/v1/documents/search/ids-by-metadata" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"project": "beta"}'
```

### Favorites

```bash
# Toggle favorite
curl -X PUT -H "Authorization: Bearer <token>" \
  "http://localhost:8081/api/v1/favorites/<documentId>/toggle"

# Check if favorite
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8081/api/v1/favorites/<documentId>/is-favorite"
```

### Recycle Bin

```bash
# List deleted items
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8081/api/v1/recycle-bin"

# Restore items
curl -X POST "http://localhost:8081/api/v1/recycle-bin/restore" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"ids": ["<id1>", "<id2>"]}'

# Empty recycle bin
curl -X DELETE -H "Authorization: Bearer <token>" \
  "http://localhost:8081/api/v1/recycle-bin/empty"
```

### Audit Trail

```bash
# Get audit for a document
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8081/api/v1/audit/<documentId>"

# Verify audit chain integrity
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8081/api/v1/audit/verify"
```

### Dashboard

```bash
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8081/api/v1/dashboard/statistics"
```

Returns: total files, total folders, storage breakdown by content type, and file type distribution.

### AI Chat

> **Requires `openfilz.ai.active=true`**. These endpoints are not available when AI is disabled.

The AI chat API uses **Server-Sent Events (SSE)** for streaming responses. The AI assistant can answer questions about your documents using RAG (Retrieval-Augmented Generation) and perform document management actions via function calling.

#### Start or Continue a Conversation

```bash
# New conversation
curl -X POST "http://localhost:8081/api/v1/ai/chat" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"message": "What documents do I have about project alpha?"}'

# Continue existing conversation
curl -X POST "http://localhost:8081/api/v1/ai/chat" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"message": "Can you move them to a new folder?", "conversationId": "<conversation-uuid>"}'
```

The response is a stream of SSE events, each containing an `AiChatResponse` object:

```json
{"conversationId": "...", "content": "I found ", "type": "MESSAGE"}
{"conversationId": "...", "content": "3 documents", "type": "MESSAGE"}
{"conversationId": "...", "content": null, "type": "DONE"}
```

Event types:
- `MESSAGE` — a chunk of the AI response text
- `DONE` — the response is complete
- `ERROR` — an error occurred (the `content` field contains the error message)

#### List Conversations

```bash
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8081/api/v1/ai/conversations"
```

Returns a JSON array of conversations with `id`, `title`, `createdAt`, and `updatedAt`.

#### Get Conversation History

```bash
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8081/api/v1/ai/conversations/<conversationId>"
```

Returns all messages in chronological order, each with `conversationId`, `content`, and `type` (`MESSAGE`).

#### Delete a Conversation

```bash
curl -X DELETE -H "Authorization: Bearer <token>" \
  "http://localhost:8081/api/v1/ai/conversations/<conversationId>"
```

Deletes the conversation and all its messages (cascade).

#### AI Function Calling

The AI assistant can invoke the following document management tools during a conversation:

| Tool | Description |
|------|-------------|
| `listFolder` | List contents of a folder (files and folders with IDs) |
| `searchByName` | Search documents by name |
| `getDocumentInfo` | Get detailed metadata for a document |
| `createFolder` | Create a new folder |
| `moveDocuments` | Move files or folders to a different location |
| `renameDocument` | Rename a file or folder |
| `countFolderElements` | Count items in a folder |
| `getDocumentPath` | Get the full path of a document from root |

These tools are invoked automatically by the LLM when the user's request requires an action. The results are incorporated into the AI response.

---

## GraphQL API

### Endpoint and Explorer

| Resource | URL |
|----------|-----|
| Endpoint | `http://<host>:8081/graphql/v1` |
| GraphiQL | `http://<host>:8081/graphql/v1` (browser) |

The interactive **GraphiQL** explorer lets you build and test queries with auto-completion and schema documentation.

### Queries

#### List Folder Contents

```graphql
query {
  listFolder(request: {
    id: null,              # null = root folder; or a folder UUID
    pageInfo: {
      pageNumber: 0,
      pageSize: 25,
      sortBy: "name",
      sortOrder: ASC
    }
  }) {
    id
    name
    type
    contentType
    size
    createdAt
    updatedAt
    createdBy
    updatedBy
    favorite
    thumbnailUrl
    metadata
  }
}
```

#### Get Document by ID

```graphql
query {
  documentById(id: "550e8400-e29b-41d4-a716-446655440000") {
    id
    name
    type
    contentType
    size
    metadata
    createdAt
    createdBy
  }
}
```

#### Count Items

```graphql
query {
  count(request: { id: null }) # count root items; pass pageInfo: null
}
```

**Important:** Count queries must pass `pageInfo: null` (no pagination). List queries require `pageInfo` with at least `pageNumber` and `pageSize`.

#### List Favorites

```graphql
query {
  listFavorites(request: {
    pageInfo: { pageNumber: 0, pageSize: 25 }
  }) {
    id
    name
    type
    size
  }
}

query {
  countFavorites(request: { pageInfo: null })
}
```

#### Full-Text Search (requires OpenSearch)

```graphql
query {
  searchDocuments(
    query: "quarterly report",
    page: 0,
    size: 20
  ) {
    totalHits
    documents {
      id
      name
      contentType
      size
    }
  }
}
```

### Filtering and Pagination

The `ListFolderRequest` input supports rich filtering:

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Folder ID (`null` for root) |
| `type` | DocumentType | `FILE` or `FOLDER` |
| `contentType` | String | MIME type filter |
| `name` | String | Exact name match |
| `nameLike` | String | Partial name match (contains) |
| `metadata` | JSON | JSONB contains filter |
| `size` | Long | Exact size match |
| `createdBy` | String | Creator email |
| `updatedBy` | String | Last updater email |
| `createdAtAfter` / `createdAtBefore` | DateTime | Date range filter |
| `updatedAtAfter` / `updatedAtBefore` | DateTime | Date range filter |
| `favorite` | Boolean | Filter by favorite status |
| `active` | Boolean | Filter by active/deleted status |
| `pageInfo` | PageInfo | Pagination and sorting (required for list queries) |

**PageInfo:**

```graphql
input PageInfo {
  pageNumber: Int!    # 0-based
  pageSize: Int!      # items per page
  sortBy: String      # field name (e.g., "name", "updatedAt", "size")
  sortOrder: SortOrder # ASC or DESC
}
```

### Custom Scalars

| Scalar | Format | Example |
|--------|--------|---------|
| `UUID` | RFC 4122 | `"550e8400-e29b-41d4-a716-446655440000"` |
| `DateTime` | ISO 8601 | `"2025-03-15T10:30:00Z"` |
| `JSON` | JSON object | `{"key": "value"}` |
| `Long` | 64-bit integer | `1073741824` |

---

## Official SDKs

OpenFilz provides 5 official SDKs auto-generated from the OpenAPI specification. Every SDK stays in sync with the API and bundles the GraphQL schema files.

### Available SDKs

| SDK | Language | Package | Runtime |
|-----|----------|---------|---------|
| **Java** (blocking) | Java 25+ | [`org.openfilz:openfilz-sdk-java`](https://central.sonatype.com/artifact/org.openfilz/openfilz-sdk-java) | OkHttp + Gson |
| **Java** (reactive) | Java 25+ / Spring 3+ | [`org.openfilz:openfilz-sdk-java-reactive`](https://central.sonatype.com/artifact/org.openfilz/openfilz-sdk-java-reactive) | WebClient + Reactor |
| **TypeScript** | Node.js 18+ | [`@openfilz-sdk/typescript`](https://www.npmjs.com/package/@openfilz-sdk/typescript) | Axios |
| **Python** | Python 3.9+ | [`openfilz-sdk-python`](https://pypi.org/project/openfilz-sdk-python/) | urllib3 + Pydantic |
| **C# / .NET** | .NET 8.0+ | [`OpenFilz.Sdk`](https://www.nuget.org/packages/OpenFilz.Sdk) | HttpClient + Generic Host |

### Installation

**Java (Maven)**

```xml
<dependency>
  <groupId>org.openfilz</groupId>
  <artifactId>openfilz-sdk-java</artifactId>
  <version>LATEST</version>
</dependency>
```

**Java Reactive (Maven)**

```xml
<dependency>
  <groupId>org.openfilz</groupId>
  <artifactId>openfilz-sdk-java-reactive</artifactId>
  <version>LATEST</version>
</dependency>
```

**TypeScript (npm)**

```bash
npm install @openfilz-sdk/typescript
```

**Python (pip)**

```bash
pip install openfilz-sdk-python
```

**C# (.NET CLI)**

```bash
dotnet add package OpenFilz.Sdk
```

### Quick Start Examples

#### Java (Blocking)

```java
var client = new ApiClient().setBasePath("https://api.example.com");
client.setAccessToken("your-jwt-token");

var api = new DocumentControllerApi(client);
var response = api.uploadDocument(file, parentId, null);
System.out.println("Uploaded: " + response.getName());
```

#### Java (Reactive)

```java
var client = new ApiClient().setBasePath("https://api.example.com");
client.setBearerToken("your-jwt-token");

var api = new DocumentControllerApi(client);
api.uploadDocument(file, parentId, null)
   .flatMap(uploaded -> api.getDocumentInfo(uploaded.getId()))
   .subscribe(doc -> System.out.println(doc.getName()));
```

#### TypeScript

```typescript
import { Configuration, DocumentControllerApi } from '@openfilz-sdk/typescript';

const config = new Configuration({
  basePath: 'https://api.example.com',
  accessToken: 'your-jwt-token',
});

const api = new DocumentControllerApi(config);
const response = await api.uploadDocument(file, parentId);
console.log('Uploaded:', response.data.name);
```

#### Python

```python
from openfilz_sdk import Configuration, ApiClient, DocumentControllerApi

config = Configuration(
    host="https://api.example.com",
    access_token="your-jwt-token"
)

with ApiClient(config) as client:
    api = DocumentControllerApi(client)
    response = api.upload_document(file=file, parent_id=parent_id)
    print(f"Uploaded: {response.name}")
```

#### C# / .NET

```csharp
var host = Host.CreateDefaultBuilder()
    .ConfigureApi((ctx, services, options) =>
    {
        options.AddTokens(new BearerToken("your-jwt-token"));
        options.AddApiHttpClients(c =>
            c.BaseAddress = new Uri("https://api.example.com"));
    })
    .Build();

var api = host.Services.GetRequiredService<IDocumentControllerApi>();
var response = await api.UploadDocument1Async(file, parentId);
var uploaded = response.Ok()!;
Console.WriteLine($"Uploaded: {uploaded.Name}");
```

### Why Use the SDKs?

- **Zero boilerplate** — pre-built, strongly-typed API clients
- **Always in sync** — auto-generated from the same OpenAPI spec
- **GraphQL included** — schema files bundled for combined REST + GraphQL usage
- **Full coverage** — all endpoints accessible from day one
- **Idiomatic** — each SDK follows its ecosystem's conventions (`Mono`/`Flux` for reactive Java, `async`/`await` for C# and TypeScript, context managers for Python)

---

## Keycloak Service Account Setup

This section provides a complete, step-by-step example of setting up machine-to-machine authentication. See [Authentication > Service Account Tokens](#service-account-tokens-server-to-server) above for the detailed walkthrough.

**Summary:**

1. Create a confidential client in Keycloak with **Service accounts roles: ON**
2. Copy the **Client secret** from the Credentials tab
3. Assign the desired **roles** (READER, CONTRIBUTOR, CLEANER, AUDITOR) to the service account
4. Verify **protocol mappers** include realm roles (or groups) in the access token
5. Request a token via `client_credentials` grant
6. Use the token as `Authorization: Bearer <token>` on all API calls

---

## Error Handling

### HTTP Status Codes

| Status | Meaning |
|--------|---------|
| `200` | Success |
| `207` | Multi-Status (partial upload success) |
| `400` | Bad request (validation error, missing parameters) |
| `401` | Unauthorized (missing or invalid token) |
| `403` | Forbidden (insufficient role) |
| `404` | Resource not found |
| `409` | Conflict (duplicate name, concurrent modification) |
| `413` | File too large (exceeds quota) |
| `500` | Internal server error |

### Common Errors

| Error | Cause | Fix |
|-------|-------|-----|
| `UserQuotaExceededException` | Per-user storage quota exceeded | Delete files or request quota increase |
| Duplicate filename | File with same name exists in target folder | Rename the file or use the `allowDuplicates` parameter |
| Parent not found | Target folder UUID doesn't exist | Verify folder ID; use `null` for root |

---

## Rate Limits and Quotas

OpenFilz does not impose API rate limits. However, your administrator may configure:

| Quota | Property | Description |
|-------|----------|-------------|
| Per-file size | `openfilz.quota.file-upload` | Maximum file size in MB (`0` = unlimited) |
| Per-user storage | `openfilz.quota.user` | Total storage per user in MB (`0` = unlimited) |
| TUS max upload | `openfilz.tus.max-upload-size` | Maximum resumable upload size (default: 10 GB) |
