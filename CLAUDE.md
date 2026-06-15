# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

# OpenFilz Core — Architecture Guide

## Project Overview

OpenFilz is an Open Source Document Management System (DMS) built with Spring Boot 4, Spring WebFlux (reactive), and R2DBC.

**Version:** 1.1.23-SNAPSHOT
**License:** AGPL-3.0

**Key Components:**
- `openfilz-api` — Core DMS service (REST + GraphQL)
- `openfilz-sdk` — Multi-language SDKs (Java, Java Reactive, Python, TypeScript, C#)

**Tech Stack:**
Java 25, Spring Boot 4+ (Spring Framework 7, Jackson 3), WebFlux, R2DBC, PostgreSQL, MinIO/S3, OAuth2/JWT, OpenSearch

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

**Extensibility convention (open-core boundary):** the core provides `protected`, overridable **hooks** (Template Method) with safe defaults so downstream/extension layers can vary behaviour without core knowing about them. Do **not** bake variant-specific logic, fields, imports, or wording into core base classes — keep base classes free of concepts the core doesn't ship. When an external layer needs to influence core behaviour, add the minimal overridable seam to the base class and implement the real logic in the subclass (e.g. `AbstractOnlyOfficeService.isDocumentReadOnly(Document)` returns `false` by default and is consulted in `buildEditorConfig`). The only acceptable change to a core base class is the seam needed to enable the override.

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

## 14. GraalVM Native Image

Native images are built via the `spring-boot-maven-plugin` (Paketo Buildpacks).

**Conditional beans are evaluated at AOT (build) time.** `@ConditionalOnProperty` / `@Profile` are resolved when the image is built, not at runtime. Two patterns:
- **Build-time choice (fixed per image):** set the property in `process-aot`.
- **Runtime toggle (must flip per deployment without rebuilding):** drop `@ConditionalOnProperty`; mark both implementations `@Service @Lazy`; add a `@Configuration` factory with `@Bean @Primary` that reads the property via `@Value` and returns one impl via `ObjectProvider`. The unused impl is in the binary but never instantiated (its `@PostConstruct` never runs). Reference: `config/StorageConfig.java` (storage local vs minio). Note: `storage.type` defaults to `local`; if minio is needed in native, set `storage.type=minio` in AOT.

**Bean Validation needs reflection registration.** Hibernate Validator binds constraints to type-specific validators loaded reflectively, so they need `allDeclaredConstructors` in `reflect-config.json`, or the first request to a validated endpoint crashes with `No default constructor found`. `@Positive`/`@Negative` and friends are not registered by default — prefer `@Min(1)`/`@Max(-1)` (which use the already-registered `MinValidatorForNumber`) for integers. `@Min`/`@Max` are themselves type-specific: a constraint on a `Double`/`BigDecimal` binds to `Min/MaxValidatorForDouble` etc., which must be registered separately. The JVM never hits this (reflection works there) — only the native build does.

**Charsets & filesystem encoding (native-image.properties):** add `-H:+AddAllCharsets` (some libraries call `Charset.forName("windows-1252")`, absent from GraalVM's default subset) and `-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8` (the builder defaults to a POSIX/US-ASCII locale → `InvalidPathException` for non-ASCII filenames like `é`, `ç`, `ü`). Base64-decoding bytes to a String (e.g. TUS metadata) must pass `StandardCharsets.UTF_8` explicitly.

**Other native fixes:** Flyway migration scanning must use `ClassLoader.getResources()` (plural) instead of Spring's `PathMatchingResourcePatternResolver` to find migrations across multiple JARs (`NativeFlywayMigrationConfig`). Records/DTOs deserialized by Jackson (e.g. `TusUploadMetadata`) need `allDeclaredConstructors/Methods/Fields` in `reflect-config.json`. The global `*.json` `.gitignore` rule is negated by `!native-image-config/**/*.json` so native config JSON stays tracked. Native builds use ~5.6 GB heap each — build sequentially on machines with < 16 GB RAM.

---

## 15. SDK Modules

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

## 16. Entry Points & APIs

- **API:** `org.openfilz.dms.DmsApiApplication`
- **REST:** `/api/v1/*` (Swagger: `/swagger-ui.html`)
- **GraphQL:** `/graphql/v1`
- **Audit:** `/api/v1/audit`
- **Health:** `/actuator/health`

---

## 17. Build & Test Commands

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
$env:CI='true'; mvn -q clean verify                # CI-equivalent green build (PowerShell)
```

**OnlyOffice E2E is CI-gated — build with `CI=true` for a clean local run.** `OnlyOfficeEnd2EndIT` boots an `onlyoffice/documentserver` container plus a Chrome/Selenium `BrowserWebDriverContainer`. Its browser tests are annotated `@DisabledIfEnvironmentVariable(named="CI", matches="true")` because they need container-to-host networking that CI doesn't provide, so **CI never runs them** — the authoritative green baseline excludes them. Run locally without that flag and a couple of them flake (Chrome can't reliably fetch `api.js` from the OnlyOffice container); this is orthogonal to anything Spring-side, not a regression signal. Set `CI=true` (PowerShell: `$env:CI='true'`) to skip exactly those tests; nothing is `@EnabledIfEnvironmentVariable`, so `CI=true` never turns tests on.

### Coverage
The merged JaCoCo report (unit + integration via failsafe) is produced by `verify` at `openfilz-api/target/jacoco/jacoco.csv`. Measure locally with `$env:CI='true'; mvn -q clean verify` (CI=true gates the flaky OnlyOffice ITs above; ensure `JAVA_HOME` points at a real JDK 25). A full run is ~12 min across the Postgres/Keycloak/MinIO/OpenSearch/Gotenberg testcontainers. JaCoCo line-missed counts only lines with zero instructions (partially-covered lines count as covered), so to find cheap wins target fully-uncovered lines (`ci==0 and mi>0` in `jacoco.xml`) — typically error/edge branches in `service.impl`, reachable with Mockito (stub async clients to throw to hit catch blocks) + `ReflectionTestUtils.invokeMethod` for private helpers. Use `doReturn(...).when(...)` for methods returning `Mono<? extends X>` (wildcard capture breaks `thenReturn`). Note: the committed coverage badge under `.github/badges/` can read low/misleading versus real merged coverage — trust the merged CSV, not the badge.

### Spring Boot 4 / Jackson 3 notes
The codebase targets Spring Boot 4 (Spring Framework 7, Jackson 3, Testcontainers 2). Non-obvious points when bumping deps or adding tests:
- **Test client:** `@SpringBootTest(RANDOM_PORT)` no longer auto-provides a server-bound `WebTestClient`; `@AutoConfigureWebTestClient` (relocated to `org.springframework.boot.webtestclient.autoconfigure`) only builds a mock-bound client. Provide a `@Lazy WebTestClient.bindToServer().baseUrl("http://localhost:" + env.getProperty("local.server.port"))` bean in the shared test config (see `GraphQlTestConfig`).
- **Windows-only upload hang:** Netty 4.2 FFM buffers break the JDK `AsynchronousFileChannel` on Windows (netty#16071), silently hanging multipart upload/download tests. Run tests with `-Dio.netty.noPreferDirect=true` (surefire/failsafe argLine) AND avoid `DataBufferUtils.write(flux, Path)` (async channel) for received network buffers — use the `WritableByteChannel` overload. Linux is unaffected, so CI can pass while Windows dev hangs.
- **Module splits (beans silently missing):** `WebClient.Builder` → add `spring-boot-webclient`; Flyway → `spring-boot-starter-flyway` (not `flyway-core`); OAuth2 → `spring-boot-starter-security-oauth2-resource-server`. Auto-config relocations: `o.s.b.autoconfigure.r2dbc.*` → `o.s.b.r2dbc.autoconfigure.*` (data-r2dbc classes also renamed: `R2dbcDataAutoConfiguration` → `DataR2dbcAutoConfiguration`); `FlywayConfigurationCustomizer` → `o.s.b.flyway.autoconfigure.*`; `o.s.b.web.reactive.error.DefaultErrorAttributes` → `o.s.b.webflux.error.*`.
- **Testcontainers 2:** modules renamed with `testcontainers-` prefix (`testcontainers-postgresql`, `-junit-jupiter`, `-minio`, `-r2dbc`, `-selenium`); drop any pinned 1.x `testcontainers-bom`.
- **Jackson 3 (`tools.jackson`):** annotations stay `com.fasterxml.jackson.annotation`; `JsonProcessingException` → unchecked `tools.jackson.core.JacksonException` (drop now-impossible `catch (IOException)`). Mappers are immutable: build via `JsonMapper.builder()...build()`; declare the `@Primary` codec bean as `JsonMapper`; java-time support is built in (drop `JavaTimeModule`/`jackson-datatype-jsr310`). Immutable mappers kill runtime `registerSubtypes()` — core exposes a `JsonMapperCustomizer` hook for that. `JsonNode.fields()` → `properties()`, `asText()` → `asString()`. The `spring.jackson.serialization.write-dates-as-timestamps` property fails binding in Boot 4 — it moved to `spring.jackson.datatype.datetime.write-dates-as-timestamps` (don't leave an empty `serialization:` key — it crashes startup). For optional numeric request fields, set `spring.jackson.deserialization.fail-on-null-for-primitives=false` (Jackson 3 fails null→primitive by default). Library modules with explicit `com.fasterxml.jackson.core:jackson-databind` → `tools.jackson.core:jackson-databind`; generated openapi SDKs stay on Jackson 2.
- **Spring 7 API breaks:** `HttpHeaders.containsKey` → `containsHeader`; reactive `access()` lambdas must return `Mono<AuthorizationResult>`; `HttpStatus.resolve(413)` is now `CONTENT_TOO_LARGE` (not `PAYLOAD_TOO_LARGE`); `jackson2JsonEncoder()` → `jacksonJsonEncoder()`.
- **Versions known good:** springdoc 3.0.3, graphql-java-extended-scalars 24.0 (with managed graphql-java 25), tika 3.3.1 + pdfbox 3.0.7 (must match), opensearch-java 3.9.0, jjwt 0.13.0. MinIO kept at 8.6.0 (9.x is a full client rewrite — a separate migration). jjwt has no Jackson 3 adapter and stays on Spring-managed Jackson 2.

### Release / publish CI
The TypeScript SDK (`@openfilz-sdk/typescript`) publishes to npm via **OIDC Trusted Publishing** (no `NPM_TOKEN`): the publish job needs `permissions: id-token: write` (plus `contents: write` / `packages: write`, since declaring permissions zeroes the rest), Node 24 with `npm@latest` (OIDC auto-detection needs npm ≥ 11.5.1), and no `_authToken` line in `.npmrc`. The npmjs Trusted Publisher config must match the repo + workflow **filename** exactly — renaming the release workflow file requires updating it on npmjs too. The Python (twine) and C# (NuGet) SDKs still publish via API tokens.
- `npm publish` failing with **`E404 ... PUT ... Not found`** is misleading: a 404 on PUT during publish is npm's disguised "not authorized" — it almost always means an expired/invalid/under-scoped credential, NOT a missing package.

### Local Setup
```bash
cd deploy/docker-compose && docker-compose -f docker-compose.minio.yml up -d minio   # Start MinIO
cd deploy/docker-compose && make up-auth                                              # All dev services with auth
```

**Prerequisites:** Java 25+, Maven 3.x, Docker (for MinIO & integration tests)
