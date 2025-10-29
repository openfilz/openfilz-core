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
