# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

# OpenFilz Enterprise - Architecture Guide

## Project Overview

OpenFilz is an Open Source Document Management System (DMS) built with Spring Boot 3.5+, Spring WebFlux (reactive), and R2DBC.

**Key Components:**
- openfilz-api: Core DMS service (REST + GraphQL)
- openfilz-gateway: Spring Cloud Gateway

**Tech Stack:**
Java 25, Spring Boot 3.5.6, WebFlux, R2DBC, PostgreSQL, MinIO/S3, OAuth2/JWT, OpenSearch

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
- Mono<String> saveFile(FilePart)
- Mono<Resource> loadFile(storagePath)
- Mono<Void> deleteFile(storagePath)
- Mono<String> copyFile(sourceStoragePath)
- Mono<Long> getFileLength(storagePath)

### Implementations
**FileSystemStorageService:**
- Activated: storage.type=local
- Java NIO Files API
- Path: {UUID}#{filename}

**MinioStorageService:**
- Activated: storage.type=minio
- Piped streams for uploads
- WORM mode support
- I/O on boundedElastic()

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
```

---

## 3. Security & Authorization

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
  jwk-set-uri: http://lcalhost:8080/realms/.../protocol/openid-connect/certs

openfilz.security:
  no-auth: false
  worm-mode: false
  role-token-lookup: REALM_ACCESS
```

---

## 4. Reactive Patterns

### Types
- Mono<T>: Single async value
- Flux<T>: Stream of async values

### Key Patterns
1. flatMap/flatMapSequential composition
2. subscribeOn(boundedElastic()) for blocking I/O
3. Piped streams for large uploads
4. @Transactional for R2DBC

---

## 5. Database Layer (R2DBC)

### Entity (Document)
- @Table("documents")
- Fields: id, name, type, parentId, storagePath, metadata (JSONB)
- Hierarchical: parentId NULL = root

### Repositories
**DocumentRepository:** Spring Data R2DBC CRUD
**DocumentDAO:** Custom complex queries (recursive CTEs)

---

## 6. GraphQL

### Configuration
```java
@Bean RuntimeWiringConfigurer runtimeWiringConfigurer()
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
- @QueryMapping documentById()
- @QueryMapping listFolder()

---

## 7. Configuration Classes

**DmsApiApplication:** Entry point
**BaseApiConfig:** ObjectMapper
**WebFluxConfig:** HTTP codecs
**DefaultAuthSecurityConfig:** OAuth2/JWT
**GraphQlConfig:** GraphQL scalars
**Conditional Beans:** Feature toggles

---

## 8. Key Services

### DocumentService
- createFolder, uploadDocument, downloadDocument
- moveFolders, copyFolders, renameFolder, deleteFolders
- moveFiles, copyFiles, renameFile, deleteFiles
- updateMetadata, deleteMetadata
- searchDocumentsByMetadata

### DocumentServiceImpl
- Reactive composition
- Hierarchical structure
- Duplicate prevention
- JSONB metadata
- Audit trail
- ZIP downloads

### AuditService
Events: UPLOAD, DELETE, MOVE, COPY, RENAME, CREATE_FOLDER, etc.

---

## 9. REST API

Endpoints:
```
POST   /api/v1/documents/upload
POST   /api/v1/documents/upload-multiple
GET    /api/v1/documents/{id}
GET    /api/v1/documents/{id}/download
POST   /api/v1/documents/{id}/replace
PUT    /api/v1/documents/{id}
DELETE /api/v1/documents/{id}
POST   /api/v1/documents/move
POST   /api/v1/documents/copy
POST   /api/v1/documents/rename
GET    /api/v1/folders
POST   /api/v1/folders
GET    /api/v1/audit
```

---

## 10. Data Flow - Upload

1. DocumentController.uploadDocument(FilePart, parentId, metadata)
2. DocumentServiceImpl.uploadDocument()
   - Validate duplicate names
   - Validate parent exists
3. SaveDocumentService.saveFile()
   - StorageService.saveFile() → FileSystem or MinIO
   - DocumentDAO.create() → PostgreSQL
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
  
  flyway:
    url: jdbc:postgresql://...

storage:
  type: local

openfilz:
  security:
    no-auth: false
    worm-mode: false

server.port: 8081
```

---

## 12. Key Concepts

**Layered:** Controllers → Services → DAOs → Database/Storage
**Reactive:** WebFlux, R2DBC, Project Reactor
**Storage Abstraction:** Single interface, multiple implementations
**Security:** OAuth2/JWT with role-based authorization
**Audit:** Full operation tracking
**WORM Mode:** Compliance-ready read-only mode

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

## 14. Entry Points

- API: org.openfilz.dms.DmsApiApplication
- Gateway: org.openfilz.gateway.GatewayApplication

---

## 15. APIs & Documentation

- REST: /api/v1/* (Swagger: /swagger-ui.html)
- GraphQL: /graphql/v1
- Audit: /api/v1/audit
- Health: /actuator/health

---

## 16. Dashboard API

**New Endpoint**: `GET /api/v1/dashboard/statistics`

Returns aggregated dashboard metrics:
- Total files and folders count
- Storage usage breakdown by content type
- File type distribution statistics

**Backend Components**:
- `DashboardController` - REST endpoint
- `DashboardService` - Business logic
- `DashboardStatisticsResponse`, `StorageBreakdown`, `FileTypeStats` - DTOs
- `DocumentDAO` methods: `countFilesByType()`, `getTotalStorageUsed()`, `getTotalStorageByContentType()`, `countFilesByContentType()`

**Frontend Integration**:
- `DocumentApiService.getDashboardStatistics()` - REST call
- `DocumentApiService.getRecentlyEditedFiles(limit)` - GraphQL query with sort
- `DashboardComponent` - Real-time data loading with loading/error states
- Auto-refresh on navigation

---

## 17. Build & Test Commands

### Build
```bash
# Build all modules
mvn clean install

# Build only API module
mvn clean install -pl openfilz-api -am

# Build with Docker image (using jib-maven-plugin)
mvn clean install -Pkube -pl openfilz-api -am
```

### Run
```bash
# Run API (port 8081)
cd openfilz-api && mvn spring-boot:run

# Run Gateway (port 8888, optional)
cd openfilz-gateway && mvn spring-boot:run
```

### Test
```bash
# Run all tests (Testcontainers auto-starts PostgreSQL/Keycloak)
mvn test

# Run specific module tests
mvn test -pl openfilz-api
```

### Local Setup
```bash
# Start MinIO (if using MinIO storage)
cd helm/kube-deploy && docker-compose up -d minio
```

**Prerequisites:** Java 24+, Maven 3.x, Docker (for MinIO & integration tests)

---

OpenFilz prioritizes scalability, maintainability, and extensibility.
