# OpenFilz C4 Model

This document describes the architecture of the OpenFilz system using the C4 model.

## Level 1: System Context Diagram

The System Context diagram shows the software system in the context of the people and other software systems it interacts with.

```mermaid
C4Context
    title System Context Diagram for OpenFilz

    Person(user, "User", "A user of the OpenFilz system.")
    System(openfilz, "OpenFilz System", "Document Management System.")
    System_Ext(keycloak, "Keycloak", "Identity and Access Management.")

    Rel(user, openfilz, "Uses", "HTTPS")
    Rel(user, keycloak, "Authenticates", "HTTPS")
    Rel(openfilz, keycloak, "Validates Tokens", "HTTPS")
```

## Level 2: Container Diagram

The Container diagram shows the high-level technical building blocks.

```mermaid
C4Container
    title Container Diagram for OpenFilz

    Person(user, "User", "A user of the OpenFilz system.")

    System_Ext(keycloak, "Keycloak", "Identity and Access Management.")

    Container_Boundary(openfilz, "OpenFilz System") {
        Container(web_app, "Web Application", "Angular, TypeScript", "Provides the web interface for users to manage documents.")
        Container(api_app, "API Application", "Spring Boot, Java", "Provides document management functionality via GraphQL and REST APIs.")
        ContainerDb(database, "PostgreSQL", "Relational Database", "Stores document metadata, user information, and system state.")
        ContainerDb(search_engine, "OpenSearch", "Search Engine", "Stores indexed document content for full-text search.")
        ContainerDb(storage, "File System / S3", "Object Storage", "Stores the actual document files (blobs).")
    }

    Rel(user, web_app, "Visits", "HTTPS")
    Rel(web_app, keycloak, "Authenticates using", "OIDC")
    Rel(web_app, api_app, "Makes API calls to", "GraphQL / REST / HTTPS")

    Rel(api_app, keycloak, "Validates tokens with", "HTTPS")
    Rel(api_app, database, "Reads from and writes to", "R2DBC")
    Rel(api_app, search_engine, "Indexes and searches", "REST / HTTP")
    Rel(api_app, storage, "Reads from and writes to", "S3 API / File I/O")
```

## Component Details

### openfilz-api

- **Technology**: Spring Boot, Java
- **Responsibilities**:
  - Exposes GraphQL and REST endpoints.
  - Enforces security and authorization.
  - Manages document metadata in PostgreSQL.
  - Indexes document content in OpenSearch.
  - Manages file storage in S3 or Local File System.
  - Extracts text from documents (using Apache Tika).

### PostgreSQL

- **Technology**: PostgreSQL
- **Responsibilities**:
  - Persists structured data (Metadata, Folder structure, etc.).

### openfilz-web (optional)

- **Technology**: Angular 20+, TypeScript
- **Responsibilities**:
  - Renders the user interface.
  - Handles user authentication via OIDC (Keycloak).
  - Communicates with the API via GraphQL (Apollo) and REST.

### OpenSearch (optional)

- **Technology**: OpenSearch
- **Responsibilities**:
  - Provides full-text search capabilities.

### File System / S3 (optional)

- **Technology**: Local File System or MinIO/S3
- **Responsibilities**:
  - Stores binary file content.
