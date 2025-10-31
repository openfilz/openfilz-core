## The Challenge: The Pitfalls of Disparate Document Management

In any large-scale enterprise, applications frequently need to handle files—from user-uploaded images and reports to system-generated documents and logs. Without a centralized strategy, this leads to a common set of problems.

*   **Duplicated Effort:** Each development team builds its own solution for uploading, storing, organizing, and securing files, wasting valuable time and resources reinventing the wheel.
*   **Inconsistency:** Different applications implement folder structures, metadata handling, and access control in unique ways, creating data silos and making cross-system integration a nightmare.
*   **Poor Searchability:** Finding a document based on its properties (like a customer ID, creation date, or document type) becomes nearly impossible when metadata is not standardized or is stored across different databases.
*   **Security Risks:** Managing file access permissions consistently and securely across multiple systems is complex. Inconsistent implementation can easily lead to data breaches.
*   **Scalability Issues:** A simple file system approach implemented by a single application often fails to scale, leading to performance degradation as the number of documents and users grows.

## Our Solution: A Centralized, API-First Approach

The **OpenFilz API** is designed to solve these challenges by providing a single, robust, and standardized service for all document-related operations. It acts as the central source of truth for files and their associated metadata.

By abstracting away the complexities of file storage, organization, and security, this API empowers developers to treat document management as a reliable utility. Instead of building these foundational features, teams can simply consume our well-defined endpoints, allowing them to focus on delivering core business value.

## Key Benefits

Adopting this API provides significant advantages across the organization.

#### 1. **Accelerate Development and Increase Productivity**
Development teams no longer need to worry about the underlying storage technology, file I/O operations, or metadata indexing. They can integrate powerful document management capabilities into their applications with just a few API calls, drastically reducing development time and effort.

#### 2. **Centralize and Standardize**
All documents are managed through a single, consistent interface. This ensures that every file, regardless of which application uploaded it, adheres to the same rules for organization (folders), naming, and metadata. This breaks down data silos and creates a unified document ecosystem.

#### 3. **Unlock Data with Rich Metadata**
The API provides powerful tools for attaching, updating, and searching by custom metadata. This transforms a simple file repository into a smart, searchable database. You can now easily find all documents related to a specific project, user, or date range, enabling advanced workflows and business intelligence.

#### 4. **Enhance Security and Control**
Security is managed centrally. By integrating with an identity provider (like Keycloak), we ensure that all requests are authenticated and authorized according to a consistent set of rules. This significantly reduces the risk of unauthorized access and simplifies compliance audits.

#### 5. **Improve Scalability and Reliability**
As a dedicated microservice, the API is built to be highly available and scalable. It can handle a large volume of files and requests without impacting the performance of other applications. Complex operations like moving or copying large folders are handled atomically and reliably.

#### 6. **Promote a Decoupled Architecture**
Frontend applications, backend microservices, and data processing pipelines can all interact with documents through the API without needing to know about the physical storage location or implementation details. This promotes a clean, decoupled architecture that is easier to maintain and evolve over time.

## Features

### Checksum (SHA-256) Calculation

This optional feature, when enabled, calculates the SHA-256 checksum for every file uploaded to the system. The checksum is then stored as a metadata property with the key `sha256`, ensuring data integrity and providing a reliable way to verify file contents.

To activate this feature, set the following property in your `application.yml`:

```yaml
openfilz:
  calculate-checksum: true
```

### WORM (Write Once Read Many) Mode

Transform OpenFilz into a compliant archiving system with WORM mode. When enabled, this feature ensures that once a file is written, it cannot be modified or deleted, making it ideal for regulatory and long-term data retention requirements.

To enable WORM mode, you must first activate the checksum calculation feature. Additionally, you need to configure an OIDC resource server for secure authentication.

Set the following properties in your `application.yml`:

```yaml
openfilz:
  calculate-checksum: true
  security:
    worm-mode: true
```

## GraphQL API

The OpenFilz API exposes a GraphQL endpoint for powerful and flexible querying of the document repository.

### Queries

The following queries are available.

#### `listFolder(request: ListFolderRequest!): [FolderElementInfo]`

Retrieves a paginated list of files and folders within a specified folder.

*   **`request`**: A `ListFolderRequest` object used to filter and paginate the results.

Returns an array of `FolderElementInfo` objects.

#### `documentById(id: UUID!): DocumentInfo`

Fetches a single document or folder by its unique identifier.

*   **`id`**: The `UUID` of the document or folder to retrieve.

Returns a `DocumentInfo` object.

#### `count(request: ListFolderRequest): Long`

Counts the number of files and folders within a folder that match the given filter criteria.

*   **`request`**: A `ListFolderRequest` object used to filter the elements to be counted.

Returns a `Long` representing the total count.

### Input Types

#### `ListFolderRequest`

Used to filter and paginate folder content.

| Field             | Type         | Description                                               |
| ----------------- | ------------ | --------------------------------------------------------- |
| `id`              | `UUID`       | The ID of the folder to query.                            |
| `type`            | `DocumentType` | Filter by element type (`FILE` or `FOLDER`).              |
| `contentType`     | `String`     | Filter by the content type of files.                      |
| `name`            | `String`     | Filter by exact name match.                               |
| `nameLike`        | `String`     | Filter by name using a `LIKE` clause (e.g., `%.txt`).      |
| `metadata`        | `JSON`       | Filter by metadata fields (contains clause).              |
| `size`            | `Long`       | Filter by size.                                           |
| `createdAtAfter`  | `DateTime`   | Filter for elements created after this date.              |
| `createdAtBefore` | `DateTime`   | Filter for elements created before this date.             |
| `updatedAtAfter`  | `DateTime`   | Filter for elements updated after this date.              |
| `updatedAtBefore` | `DateTime`   | Filter for elements updated before this date.             |
| `createdBy`       | `String`     | Filter by the user who created the element.               |
| `updatedBy`       | `String`     | Filter by the user who last updated the element.          |
| `pageInfo`        | `PageInfo`   | Specifies pagination settings.                            |

#### `PageInfo`

Defines pagination parameters.

| Field        | Type        | Description                               |
| ------------ | ----------- | ----------------------------------------- |
| `pageNumber` | `Int!`      | The page number to retrieve (0-indexed).  |
| `pageSize`   | `Int!`      | The number of items per page.             |
| `sortBy`     | `String`    | The field to sort by (e.g., `name`).      |
| `sortOrder`  | `SortOrder` | The sort direction (`ASC` or `DESC`).     |

### Object Types

#### `FolderElementInfo`

Represents a file or folder within a list.

| Field         | Type         | Description                               |
| ------------- | ------------ | ----------------------------------------- |
| `id`          | `UUID`       | The unique identifier.                    |
| `type`        | `DocumentType` | The type of the element (`FILE` or `FOLDER`). |
| `contentType` | `String`     | The content type (MIME type) of the file. |
| `name`        | `String`     | The name of the element.                  |
| `metadata`    | `JSON`       | A JSON object for custom metadata.        |
| `size`        | `Long`       | The size of the file in bytes.            |
| `createdAt`   | `DateTime`   | The creation timestamp.                   |
| `updatedAt`   | `DateTime`   | The last modification timestamp.          |
| `createdBy`   | `String`     | The user who created the element.         |
| `updatedBy`   | `String`     | The user who last updated the element.    |

#### `DocumentInfo`

Represents a detailed view of a document or folder, including its parent.

| Field         | Type         | Description                               |
| ------------- | ------------ | ----------------------------------------- |
| `id`          | `UUID`       | The unique identifier.                    |
| `parentId`    | `UUID`       | The ID of the parent folder.              |
| `type`        | `DocumentType` | The type of the element (`FILE` or `FOLDER`). |
| `contentType` | `String`     | The content type (MIME type) of the file. |
| `name`        | `String`     | The name of the element.                  |
| `metadata`    | `JSON`       | A JSON object for custom metadata.        |
| `size`        | `Long`       | The size of the file in bytes.            |
| `createdAt`   | `DateTime`   | The creation timestamp.                   |
| `updatedAt`   | `DateTime`   | The last modification timestamp.          |
| `createdBy`   | `String`     | The user who created the element.         |
| `updatedBy`   | `String`     | The user who last updated the element.    |

### Enums

#### `DocumentType`

*   `FILE`: Represents a file.
*   `FOLDER`: Represents a folder.

#### `SortOrder`

*   `ASC`: Ascending order.
*   `DESC`: Descending order.

## REST API

The OpenFilz API provides a comprehensive RESTful interface for all document and folder management operations.

### Document Management

-   **`POST /v1/documents/upload`**: Upload a single file with optional metadata and parent folder ID.
-   **`POST /v1/documents/upload-multiple`**: Upload multiple files simultaneously.
-   **`PUT /v1/documents/{documentId}/replace-content`**: Replace the content of an existing file.
-   **`PUT /v1/documents/{documentId}/replace-metadata`**: Replace all metadata for a document or folder.
-   **`PATCH /v1/documents/{documentId}/metadata`**: Update or add specific metadata fields.
-   **`DELETE /v1/documents/{documentId}/metadata`**: Delete specified metadata keys from a document.
-   **`GET /v1/documents/{documentId}/download`**: Download a single file.
-   **`POST /v1/documents/download-multiple`**: Download multiple documents as a single ZIP file.
-   **`POST /v1/documents/search/ids-by-metadata`**: Find document IDs that match specified metadata criteria.
-   **`POST /v1/documents/{documentId}/search/metadata`**: Retrieve metadata for a document, with an option to filter by keys.
-   **`GET /v1/documents/{documentId}/info`**: Get detailed information for a specific document.

### File Management

-   **`POST /v1/files/move`**: Move a set of files to a different folder.
-   **`POST /v1/files/copy`**: Copy a set of files to a different folder.
-   **`PUT /v1/files/{fileId}/rename`**: Rename an existing file.
-   **`DELETE /v1/files`**: Delete a set of files.

### Folder Management

-   **`POST /v1/folders`**: Create a new folder.
-   **`POST /v1/folders/move`**: Move a set of folders (including their contents) to a different folder.
-   **`POST /v1/folders/copy`**: Copy a set of folders (including their contents) to a different folder.
-   **`PUT /v1/folders/{folderId}/rename`**: Rename an existing folder.
-   **`DELETE /v1/folders`**: Delete a set of folders and their contents.
-   **`GET /v1/folders/list`**: List the files and subfolders within a specific folder.
-   **`GET /v1/folders/count`**: Count the number of files and subfolders within a specific folder.

### Audit Trail

-   **`GET /v1/audit/{id}`**: Retrieve the audit trail for a specific resource.
-   **`POST /v1/audit/search`**: Search for audit trails based on specified parameters.

## Security

The OpenFilz API provides flexible security options.

### Disabling Security

For development or testing purposes, security can be completely disabled by setting the `openfilz.security.no-auth` property to `true` in your `application.yml`.

```yaml
openfilz:
  security:
    no-auth: true
```

### Enabled Security (OIDC Resource Server)

When security is enabled, the API acts as an OIDC resource server, validating JWT tokens for every request.

#### Default Authorization

The default authorization model uses roles extracted from the JWT token.
You can configure how roles are looked up using the `openfilz.security.role-token-lookup` property.

The available roles are:

| Role          | Description                                           |
|---------------|-------------------------------------------------------|
| `AUDITOR`     | Access to Audit trail                                 |
| `CONTRIBUTOR` | Access to all endpoints except the "Delete" ones      |
| `READER`      | Access only to read-only endpoints                    |
| `CLEANER`     | Access to all "Delete" endpoints                      |

#### Custom Authorization

For more advanced scenarios, you can provide a completely custom authorization model. To do this, you need to:

1.  Provide a custom implementation of `org.openfilz.dms.config.DefaultAuthSecurityConfig`
2.  Provide a custom implementation of `org.openfilz.dms.service.impl.SecurityServiceImpl`
3.  Set to `true` the `openfilz.security.custom-roles` property.

```yaml
openfilz:
  security:
    custom-roles: true
```

This allows you to implement complex authorization logic tailored to your specific needs.
