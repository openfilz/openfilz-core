# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

# OpenFilz Core — Architecture Guide

## Project Overview

OpenFilz is an Open Source Document Management System (DMS) built with Spring Boot 3.5+, Spring WebFlux (reactive), and R2DBC.

**Version:** 1.1.23-SNAPSHOT
**License:** AGPL-3.0

**Key Components:**
- `openfilz-api` — Core DMS service (REST + GraphQL)
- `openfilz-sdk` — Multi-language SDKs (Java, Java Reactive, Python, TypeScript, C#)

**Tech Stack:**
Java 25, Spring Boot 3.5.8, WebFlux, R2DBC, PostgreSQL, MinIO/S3, OAuth2/JWT, OpenSearch

---

## 1. Package Structure

### openfilz-api
```
org/openfilz/dms/
├── config/             Spring configurations
├── controller/         HTTP handlers (REST + GraphQL)
├── service/            Business logic interfaces + impl
├── repository/         R2DBC repositories + custom DAOs
├── entity/             R2DBC entity models
├── dto/                Request/Response DTOs
├── enums/              Role, DocumentType, AuditAction
├── exception/          Custom exceptions
├── mapper/             MapStruct mappers
└── utils/              Helpers (JwtTokenParser, etc.)
```

**Patterns:**
- Strategy: Storage abstraction (local vs MinIO)
- Template Method: AbstractSecurityService
- DAO: DocumentDAO for complex queries

---

## 2. Storage Abstraction

### StorageService Interface
All methods return Mono (reactive):
- `Mono<String> saveFile(FilePart)` — store new file
- `Mono<String> replaceFile(oldStoragePath, FilePart)` — replace file content
- `Mono<Resource> loadFile(storagePath)` — retrieve file
- `Mono<Void> deleteFile(storagePath)` — remove file
- `Mono<String> copyFile(sourceStoragePath)` — duplicate file
- `Mono<Long> getFileLength(storagePath)` — get file size

### Implementations
**FileSystemStorageService:**
- Activated: `storage.type=local`
- Java NIO Files API
- Path: `{UUID}#{filename}`

**MinioStorageService:**
- Activated: `storage.type=minio`
- Piped streams for uploads (shared `uploadToObject()` method used by both `saveFile` and `replaceFile`)
- WORM mode support
- Bucket versioning support: when `versioning-enabled=true`, `replaceFile` overwrites the same object (MinIO keeps previous versions automatically) and returns the unchanged storage path
- I/O on `boundedElastic()`

### MinioProperties (`@ConfigurationProperties(prefix = "storage.minio")`)
Centralized MinIO configuration used by `MinioStorageService` and `MinioChecksumService`. Also used by `MinioThumbnailStorageService` for endpoint/credentials (but thumbnails always use their own bucket):
- `endpoint` — MinIO server URL
- `accessKey` — MinIO access key
- `secretKey` — MinIO secret key
- `bucketName` — bucket name for document storage
- `versioningEnabled` — enables S3 bucket versioning (default: `false`)

### Configuration
```yaml
storage:
  type: local
  local.base-path: /tmp/dms-storage
  minio:
    endpoint: http://localhost:9000
    access-key: minioadmin
    secret-key: minioadmin
    bucket-name: dms-bucket
    versioning-enabled: false
```

### Thumbnail Storage
Thumbnails use a **separate** storage location from documents, controlled by `openfilz.thumbnail.storage.*`:

- **`use-main-storage=true`** (default): storage **type** follows `storage.type`, but paths/buckets come from thumbnail config
- **`use-main-storage=false`**: storage **type** follows `openfilz.thumbnail.storage.type`

In both modes:
- **Local**: path from `openfilz.thumbnail.storage.local.base-path` (fallback: `storage.local.base-path`), with `/thumbnails` subdirectory
- **MinIO**: bucket from `openfilz.thumbnail.storage.minio.bucket-name` (default: `dms-thumbnails`), endpoint/credentials from main MinIO config

**Implementations:** `FileSystemThumbnailStorageService` (local), `MinioThumbnailStorageService` (minio) — activated via `@ConditionalOnExpression`

---

## 3. TUS Protocol (Resumable Uploads)

TUS enables resumable, chunked file uploads — critical for large files or unreliable connections.

### Components
- **TusController** (`/api/v1/tus`) — Full TUS 1.0.0 protocol (OPTIONS, POST, HEAD, PATCH, DELETE, finalize)
- **TusUploadService / TusUploadServiceImpl** — Manages chunked uploads with expiration
- **TusUploadCleanupScheduler** — Background cleanup of expired uploads
- **TusProperties** — Configuration (max upload size, chunk size, expiration)

### Storage
TUS uploads use a `_tus/` prefix in both filesystem and MinIO storage. After all chunks are received, the file is finalized and moved to the standard storage path.

### Configuration
```yaml
openfilz:
  tus:
    enabled: true
```

---

## 4. Security & Authorization

### OAuth2/JWT Flow
Bearer token → ReactiveJwtDecoder → SecurityService.authorize()

### SecurityService
**AbstractSecurityService:**
- Extracts roles from JWT claims
- HTTP method to role:
  - DELETE → CLEANER
  - GET/search → READER, CONTRIBUTOR
  - POST/PUT → CONTRIBUTOR
  - /audit → AUDITOR

**SecurityServiceImpl:** Default (full CRUD)
**WormSecurityServiceImpl:** WORM mode (read-only)

### Configuration
```yaml
spring.security.oauth2.resourceserver.jwt:
  jwk-set-uri: http://localhost:8080/realms/.../protocol/openid-connect/certs

openfilz.security:
  no-auth: false
  worm-mode: false
  role-token-lookup: REALM_ACCESS
```

---

## 5. Reactive Patterns

### Types
- `Mono<T>`: Single async value
- `Flux<T>`: Stream of async values

### Key Patterns
1. `flatMap`/`flatMapSequential` composition
2. `subscribeOn(boundedElastic())` for blocking I/O
3. Piped streams for large uploads
4. `@Transactional` for R2DBC

---

## 6. Database Layer (R2DBC)

### Entity (Document)
- `@Table("documents")`
- Fields: id, name, type, parentId, storagePath, metadata (JSONB)
- Hierarchical: parentId NULL = root

### Repositories
**DocumentRepository:** Spring Data R2DBC CRUD
**DocumentDAO:** Custom complex queries (recursive CTEs)

### Migrations
Flyway with JDBC for schema migrations: `src/main/resources/db/migration/V{version}__{description}.sql`

---

## 7. GraphQL

### Configuration
```java
@Bean RuntimeWiringConfigurer runtimeWiringConfigurer();
// Registers: Json, UUID, DateTime scalars
```

### Schema (document.graphqls)
```graphql
type Query {
  listFolder(request: ListFolderRequest!): [FolderElementInfo]
  documentById(id: UUID!): DocumentInfo
  count(request: ListFolderRequest): Long
}
```

### Resolvers (DocumentQueryController)
- `@QueryMapping documentById()`
- `@QueryMapping listFolder()`

---

## 8. Key Services

### DocumentService
- createFolder, uploadDocument, downloadDocument
- moveFolders, copyFolders, renameFolder, deleteFolders
- moveFiles, copyFiles, renameFile, deleteFiles
- updateMetadata, deleteMetadata
- searchDocumentsByMetadata

### DocumentServiceImpl
- Reactive composition, hierarchical structure
- Duplicate name prevention, JSONB metadata
- Audit trail, ZIP downloads

### AuditService
Events: UPLOAD, DELETE, MOVE, COPY, RENAME, CREATE_FOLDER, etc.
Optional audit chain verification (hash chain for integrity): `openfilz.audit.chain.enabled`

### Soft Delete / Recycle Bin
- **DocumentSoftDeleteService** — marks files as deleted (keeps physical files)
- **RecycleBinController** (`/api/v1/recycle-bin`) — list, restore, permanently delete, empty
- **RecycleBinCleanupScheduler** — auto-cleanup of old deleted items
- Requires: `openfilz.soft-delete.active=true`

### Checksum Calculation
- **ChecksumService** — SHA-256 checksum per file on upload
- Implementations: `FileSystemChecksumService`, `MinioChecksumService` (with versioning support)
- **ChecksumSaveDocumentServiceImpl** — decorator that calculates checksum during save
- Configuration: `openfilz.calculate-checksum: false` (default)

### Quota Management
- **QuotaProperties** — per-file and per-user storage limits
- `openfilz.quota.file-upload` — max file size per upload (MB, 0 = no limit)
- `openfilz.quota.user` — max total storage per user (MB, 0 = no limit)
- Throws `UserQuotaExceededException` on violation

### Favorites
- **FavoriteController** (`/api/v1/favorites/{documentId}`) — add, remove, toggle, check
- **FavoriteService / FavoriteServiceImpl** — user-specific favorites
- **UserFavorite entity** — user + document association

### Thumbnail Generation
- **ThumbnailController** (`/api/v1/thumbnails/img/{documentId}`) — serve thumbnails
- **ThumbnailService / ThumbnailServiceImpl** — generate via Gotenberg and PDFBox
- **ThumbnailPostProcessor** — triggers generation after uploads
- Configuration: `openfilz.thumbnail.active`, `openfilz.thumbnail.gotenberg-url`

### OnlyOffice Integration
- **OnlyOfficeController** (`/api/v1/onlyoffice`)
  - `GET /config/{documentId}` — editor config with JWT
  - `POST /callback/{documentId}` — document save callbacks
  - `GET /status` — check if enabled
  - `GET /supported` — check file type support
- **OnlyOfficeJwtService** — JWT token generation for OnlyOffice
- Configuration: `onlyoffice.enabled`, `onlyoffice.document-server.url`, `onlyoffice.jwt.secret`

### Settings API
- **SettingsController** (`/api/v1/settings`) — exposes app config and user preferences to frontend

### Document Suggestions
- **DocumentSuggestionController** (`/api/v1/suggestions`) — autocomplete/search suggestions
- Supports filter and sort inputs

### Dashboard Statistics
- **DashboardController** (`GET /api/v1/dashboard/statistics`)
- Total files/folders, storage usage by content type, file type distribution
- **DocumentDAO** methods: `countFilesByType()`, `getTotalStorageUsed()`, etc.

---

## 9. REST API

### Core Endpoints
```
POST   /api/v1/documents/upload
POST   /api/v1/documents/upload-multiple
GET    /api/v1/documents/{id}
GET    /api/v1/documents/{id}/download
PUT    /api/v1/documents/{id}/replace-content
PUT    /api/v1/documents/{id}
DELETE /api/v1/documents/{id}
POST   /api/v1/documents/move
POST   /api/v1/documents/copy
POST   /api/v1/documents/rename
GET    /api/v1/folders
POST   /api/v1/folders
GET    /api/v1/audit
GET    /api/v1/dashboard/statistics
GET    /api/v1/settings
GET    /api/v1/suggestions
```

### TUS Endpoints
```
POST   /api/v1/tus                    Create upload
HEAD   /api/v1/tus/{uploadId}         Get upload progress
PATCH  /api/v1/tus/{uploadId}         Upload chunk
POST   /api/v1/tus/{uploadId}/finalize  Complete upload
DELETE /api/v1/tus/{uploadId}         Cancel upload
```

### Favorites
```
POST   /api/v1/favorites/{documentId}          Add favorite
DELETE /api/v1/favorites/{documentId}          Remove favorite
PUT    /api/v1/favorites/{documentId}/toggle   Toggle favorite
GET    /api/v1/favorites/{documentId}/is-favorite  Check status
```

### Recycle Bin
```
GET    /api/v1/recycle-bin            List deleted items
GET    /api/v1/recycle-bin/count      Count deleted items
POST   /api/v1/recycle-bin/restore    Restore items
DELETE /api/v1/recycle-bin            Permanently delete
DELETE /api/v1/recycle-bin/empty      Empty recycle bin
```

### Thumbnails & OnlyOffice
```
GET    /api/v1/thumbnails/img/{documentId}      Get thumbnail
GET    /api/v1/onlyoffice/config/{documentId}   OnlyOffice config
POST   /api/v1/onlyoffice/callback/{documentId} OnlyOffice callback
GET    /api/v1/onlyoffice/status                Check status
GET    /api/v1/onlyoffice/supported             Check file support
```

---

## 10. Data Flow — Upload

1. DocumentController.uploadDocument(FilePart, parentId, metadata)
2. DocumentServiceImpl.uploadDocument()
   - Validate duplicate names, validate parent exists, check quota
3. SaveDocumentService.saveFile()
   - StorageService.saveFile() → FileSystem or MinIO
   - DocumentDAO.create() → PostgreSQL
   - ChecksumService.calculate() (if enabled)
   - ThumbnailPostProcessor.process() (if active)
   - MetadataPostProcessor → OpenSearch
   - AuditService.createAuditLog(UPLOAD)
4. Return UploadResponse

All reactive (Mono), non-blocking.

---

## 11. Configuration Properties

```yaml
spring:
  application.name: openfilz-api
  threads.virtual.enabled: true

  r2dbc:
    url: r2dbc:postgresql://...
    pool: {initial-size: 5, max-size: 10}

  security.oauth2.resourceserver.jwt:
    jwk-set-uri: http://keycloak:8080/realms/.../protocol/openid-connect/certs

  graphql.http.path: /graphql/v1
  flyway.url: jdbc:postgresql://...

storage:
  type: local                          # local | minio
  minio:
    endpoint: http://localhost:9000
    access-key: minioadmin
    secret-key: minioadmin
    bucket-name: dms-bucket
    versioning-enabled: false

openfilz:
  security:
    no-auth: false
    worm-mode: false
  tus:
    enabled: true
  soft-delete:
    active: false
  calculate-checksum: false
  quota:
    file-upload: 0                     # MB, 0 = no limit
    user: 0                            # MB, 0 = no limit
  thumbnail:
    active: false
    gotenberg-url: http://gotenberg:3000
    storage:
      use-main-storage: true
      minio.bucket-name: dms-thumbnails
  audit:
    chain:
      enabled: false
      algorithm: SHA-256

onlyoffice:
  enabled: false
  document-server:
    url: http://onlyoffice
  jwt:
    secret: secret

server.port: 8081
```

---

## 12. Key Concepts

**Layered:** Controllers → Services → DAOs → Database/Storage
**Reactive:** WebFlux, R2DBC, Project Reactor
**Storage Abstraction:** Single interface, multiple implementations
**Security:** OAuth2/JWT with role-based authorization
**Audit:** Full operation tracking with optional hash chain verification
**WORM Mode:** Compliance-ready read-only mode
**Bucket Versioning:** MinIO versioning support for file replace operations
**TUS Protocol:** Resumable/chunked uploads for large files
**Soft Delete:** Recycle bin with auto-cleanup
**Thumbnails:** Server-side generation via Gotenberg/PDFBox

---

## 13. For Developers

**Add REST endpoint:**
1. Create DTOs (request, response)
2. Add method to DocumentService
3. Implement in DocumentServiceImpl
4. Add controller method

**Add GraphQL query:**
1. Add to document.graphqls
2. Add @QueryMapping

**Change storage:**
1. Implement StorageService
2. Use @ConditionalOnProperty
3. Add config

**Modify security:**
1. Extend AbstractSecurityService
2. Register with condition
3. Update openfilz.security config

---

## 14. SDK Modules

```
openfilz-sdk/
├── openfilz-sdk-java/              Synchronous Java client
├── openfilz-sdk-java-reactive/     Reactive Java client (WebFlux)
├── openfilz-sdk-python/            Python client
├── openfilz-sdk-typescript/        TypeScript/Node.js client
├── openfilz-sdk-csharp/            C# client
└── openfilz-sdk-samples-test/      Sample code and integration tests
```

---

## 15. Entry Points & APIs

- **API:** `org.openfilz.dms.DmsApiApplication`
- **REST:** `/api/v1/*` (Swagger: `/swagger-ui.html`)
- **GraphQL:** `/graphql/v1`
- **Audit:** `/api/v1/audit`
- **Health:** `/actuator/health`

---

## 16. Build & Test Commands

### Build
```bash
mvn clean install                                    # Build all modules
mvn clean install -pl openfilz-api -am              # Build API only
mvn clean install -Pkube -pl openfilz-api -am       # Docker image (jib-maven-plugin)
```

### Run
```bash
cd openfilz-api && mvn spring-boot:run              # Run API (port 8081)
```

### Test
```bash
mvn test                                            # All tests (Testcontainers auto-starts PostgreSQL/Keycloak)
mvn test -pl openfilz-api                           # API tests only
```

### Local Setup
```bash
cd deploy/docker-compose && docker-compose -f docker-compose.minio.yml up -d minio   # Start MinIO
cd deploy/docker-compose && make up-auth                                              # All dev services with auth
```

**Prerequisites:** Java 25+, Maven 3.x, Docker (for MinIO & integration tests)
