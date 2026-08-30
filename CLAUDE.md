# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

# OpenFilz Core — Architecture Guide

## Project Overview

OpenFilz is an Open Source Document Management System (DMS) built with Spring Boot 4, Spring WebFlux (reactive), and R2DBC.

**Version:** 1.2.18-SNAPSHOT
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
  - e-Sign writes → CONTRIBUTOR (+ SIGN_REQUESTER when `openfilz.signature.require-requester-role=true`)

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

### e-Sign (electronic signatures)
See `docs/esign.md`. Envelope engine in core (`SignatureService`/`SignatureTemplateService`/`SignaturePdfService`, controllers `SignatureController`/`SignatureTemplateController`/`PublicSignatureController`, Flyway `V1_7`). Runtime toggle `openfilz.signature.active` (controllers answer 404 when off, `SignaturePublicSecurityConfig` uses the sentinel-path trick, surfaced as `Settings.signatureActive`). Edition seams live in `service/signature/` (`SignatureAccessPolicy`, `SignatureActorResolver`, `SignatureNotifier`, `SignatureMailer`, `SignatureSealer`, `SignatureOtpSender`, `SignatureCompletionListener`) with permissive/no-op core defaults — EE overrides them `@Primary`; `AbstractSecurityService.isSignatureAuthorized` is the role hook (writes need CONTRIBUTOR, plus the `SIGN_REQUESTER` role when the runtime toggle `openfilz.signature.require-requester-role` is on — surfaced as `Settings.signatureRequesterRoleRequired`). Sealers (`InProcessSignatureSealer` self-signed/pkcs12, `CloudSignatureSealer` hash-only to sign.openfilz.com) are plain classes chosen at runtime in `SignatureConfig` so exactly one core `SignatureSealer` bean exists (EE injects it as fallback via `@Qualifier(SignatureConfig.CORE_SEALER)`). `spring-boot-starter-mail` + Bouncy Castle were added for it; mails are localised from `signature-mail/messages_*.properties`. ITs under `e2e/signature` capture signing links through a `@Primary` test `SignatureMailer`; they also run inside the EE build, so they use `admin-user` (has the EE `EDIT_SHARE` role) as initiator.

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

**Spring Data AOT generates broken R2DBC entity accessors on a clean build (ship a `process-aot` "primer").** With Spring Data 4.0.x, the **first** `spring-boot:process-aot` after a clean generates per-entity `__Accessor_`/`__Instantiator_` classes that are wrong in the native image: a Postgres `BOOLEAN` column reaches the entity's primitive `boolean` field as a `java.lang.Integer` → `IllegalArgumentException: Can not set boolean field … to java.lang.Integer` from `R2dbcEntityTemplate` (other typed columns are affected too). AOT generation is **non-deterministic**: a *second* `process-aot` over unchanged sources omits the accessors (the first run's classes are now on its input classpath), so the reflective path is used and reads work. Net effect: clean builds (CI/release) ship the broken image while incremental dev builds work — the same commit passes native e2e locally and crashes from the pipeline. A `@ReadingConverter Converter<Integer,Boolean>` does **not** fix it when the accessors are present; the JVM never hits it. **Workaround:** run a throwaway `process-aot` *before* the real native build so the build's own pass is always the second run. For container/multistage builds the primer must run **inside** the build container (a host-side primer can't reach a compile that runs in the container) — see `openfilz-enterprise/CLAUDE.md` (backup-api `Dockerfile.native-multistage`).

**Caffeine caches need reflection hints — for BOTH generated families.** `Caffeine.build()` assembles a cache from two families of generated classes and resolves *both* by name from the builder options in effect: `LocalCacheFactory` picks the `BoundedLocalCache` subclass (`SSMSA` for `maximumSize + expireAfterAccess`) via `findClass` + `findStaticVarHandle(clazz, "FACTORY")` (constructor fallback), and `NodeFactory` picks the entry class (`PSAMS` for the same options) via `findClass` + `findConstructor`. Every lookup takes a non-constant class, so native-image cannot intrinsify them and drops the classes as unreachable — the first `build()` dies at runtime with `IllegalStateException: <name>` caused by `ClassNotFoundException` (the JVM is unaffected, so only native deployments hit it). `CaffeineRuntimeHints` registers the **whole `com.github.benmanes.caffeine.cache` package** (declared fields + constructors). Do not narrow it to predicted names: v1.8.7 modelled only the cache family's alphabet (`S W I L M A R`) and shipped an image that still died one frame later on the node family (`P F D …`). Verified in a standalone GraalVM probe: no config → fails on `SSMSA`; cache family only → fails on `PSAMS`; whole package → works.

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

### Spring AI 2.0 notes (AI document chat)

The AI feature (`openfilz.ai.active=true`) runs on Spring AI 2.0. Five things bite when upgrading from 1.x:
- **Provider `enabled` flags are gone.** `spring.ai.<provider>.chat.enabled` no longer exists; each provider's auto-configuration is gated on `spring.ai.model.chat` / `spring.ai.model.embedding` (value = provider name, or `none`), and those conditions are `matchIfMissing = true` — so with both the Ollama and OpenAI starters on the classpath and no selector set, **both** providers' models get created. `AiModelProviderEnvironmentPostProcessor` derives the selectors so that `openfilz.ai.active` is the single switch: off ⇒ everything `none` (nothing is built, matching the beans, which are all conditional on that flag); on ⇒ chat and embedding resolve independently from the `openfilz.ai.<provider>.<kind>.enabled` booleans (`OLLAMA_CHAT_ENABLED` / `OPENAI_CHAT_ENABLED` …), Ollama winning ties and serving as the fallback when a kind has no switch, since its defaults target a stock local install. An explicit `spring.ai.model.*` always wins. The post-processor must stay ordered after `ConfigDataEnvironmentPostProcessor` or the switches read as absent. Tests that mock the models pin `spring.ai.model.*=none`, or the real models collide with the mocks.
- **The embedding model is a one-time deployment decision — the chat model is not.** The chat LLM is stateless and can be swapped freely; every vector in `vector_store` was produced by one specific embedding model, and vectors from different models are incomparable (changing the model silently breaks similarity search, it doesn't degrade it). `EmbeddingRegistryGuard` records the configured provider/model in `ai_embedding_registry` (`V1_5`, ai-migration) on first AI-enabled startup and on later startups refuses to start (default `openfilz.ai.embedding.validation=fail-fast`; `warn` starts anyway) when the configuration changed while indexed vectors exist — the ways out are restoring the old config or `TRUNCATE TABLE vector_store; DELETE FROM ai_embedding_registry` plus re-embedding (re-uploading) documents. It also checks `EmbeddingModel.dimensions()` against the fixed `vector(768)` column (`AiConfig.EMBEDDING_DIMENSIONS`); OpenAI's `text-embedding-3-*` therefore get `spring.ai.openai.embedding.dimensions=768` (native shortening) in `application.yml`. The guard skips when the `spring.ai.model.embedding` selector is `none`/absent (mocked tests), and treats unresolvable dimensions (provider down, mock returning 0) as unknown rather than failing.
- **The frontend follows the backend flag.** `openfilz.ai.active` is surfaced as `aiActive` on `GET /api/v1/settings` (same shape as `thumbnailsActive`), and openfilz-web gates its chat FAB on that — there is no `NG_APP_*` AI toggle to keep in sync.
- **Tool execution moved to a `ToolCallingAdvisor`** on the ChatClient. Only the *auto-configured* `ChatClient.Builder` registers it — a client built from `ChatClient.builder(chatModel)` emits tool-call requests nobody executes. `ChatClientAssembler` registers the advisor explicitly.
- **`ChatModel.getOptions()` must not be null**: `DefaultChatClientUtils` calls `getOptions().mutate()` on every request, so mocked chat models need it stubbed.
- **Swagger annotations clash.** `spring-ai-bom` pins `swagger-annotations-jakarta` below the swagger-core springdoc runs on, and **both `openai-java-core` and `anthropic-java-core`** drag in the pre-Jakarta `swagger-annotations` (same `io.swagger.v3.oas.annotations` package). Either one makes springdoc emit a bogus `"default": ""` on every schema property, which generates uncompilable enum fields (`private TypeEnum type = ;`) in the SDK modules — a failure that surfaces *after* all tests pass. The root pom pins `swagger-annotations-jakarta` ahead of the BOM imports and `openfilz-api` excludes the non-Jakarta artifact from both starters.

### Chat providers & per-user BYOK

Four chat providers ship: **Ollama, Anthropic (Claude), Google Gemini (GenAI/Developer API), OpenAI** — priority when several `openfilz.ai.<provider>.chat.enabled` switches are on: **ollama > anthropic > google > openai** (selector values `ollama` / `anthropic` / `google-genai` / `openai`). Anthropic and Gemini are **chat-only**: embeddings stay Ollama/OpenAI (no Anthropic embeddings API; pgvector schema pinned to 768 dims), and their `embedding.enabled` switches are ignored.

- **Per-user BYOK** (`openfilz.ai.user-settings.enabled` + `AI_SETTINGS_ENCRYPTION_KEY`, both read at *runtime* — native-safe): users override the chat model via `/api/v1/settings/ai` (`AiSettingsController`); keys stored AES-256-GCM (`AiSettingsCipher`) in `user_ai_settings` (ai-migration `V1_6` — `V1_5` is the embedding registry). `UserChatClientResolver` builds provider `ChatModel`s **programmatically** via the `*Setup.setupSyncClient/setupAsyncClient` helpers and caches them per user (Caffeine, config-hash invalidation).
- **Both sync AND async vendor clients must be passed** to `OpenAiChatModel.builder()` / `AnthropicChatModel.builder()` — a missing one is self-built from env vars and fails with "at least one credential source must be specified" when only a BYOK key exists.
- **There is no `ChatClient` bean.** `ChatClientAssembler.assemble(model, tools)` builds the client per request with a **fresh `DocumentAiTools` from `DocumentAiToolsFactory`** — the doc-link registry is per-turn state (the old singleton cross-contaminated concurrent users), and per-request tools route `describeImage` to the user's model.
- **Conversations are user-owned**: stamped `created_by`, filtered on list, 404 on foreign access; `created_by IS NULL` legacy rows stay visible to everyone.
- **Native image**: Spring AI 2.0's `AnthropicChatModel` wraps the official `com.anthropic:anthropic-java-core` SDK, which ships **no** GraalVM metadata → `AnthropicSdkRuntimeHints` (jar-scan registrar like `PoiOoxmlRuntimeHints`) registers `com.anthropic.models/core`. The Google GenAI SDK bundles complete `META-INF/native-image` configs + Spring ships `GoogleGenAiRuntimeHints`. A real native build validation is still pending (release pipeline).

### AI security (per-user access policy) & native-safe runtime toggle

- **`openfilz.ai.active` is a RUNTIME toggle — never a bean condition.** No AI bean carries `@ConditionalOnProperty` anymore (bean conditions are evaluated at build time in GraalVM native images). Instead: `AiProperties.active` is bound at runtime; the two controllers are always mapped and return 404 per request when off; every other AI bean is `@Lazy` (so a disabled deployment never initializes the pipeline and a JVM deployment without provider models never resolves them); `EmbeddingRegistryGuard` (eager runner, `ObjectProvider` deps) and `aiFlywayCustomizer` self-guard on the flag at runtime. Native images must run `process-aot` with `-Dopenfilz.ai.active=true` so the Spring AI provider auto-configurations (still selector-gated) are compiled in — the EE collaboration pom does this in both native profiles.
- **`AiAccessPolicy` — the AI feature's per-user document access seam.** The tools resolve documents by name via raw repository queries and read content straight from storage, so endpoint-level authorization alone cannot protect per-document permissions. Every read/modify decision (tool queries, name resolution, content reads, mutations, RAG chunks) goes through `AiAccessPolicy` with the requesting user's email. Core default `PermitAllAiAccessPolicy` (no per-document permissions in CE); the enterprise layer overrides with a `@Primary` policy backed by its ownership/share model. Policy implementations must not rely on the reactive security context (tool threads don't have it).
- **Tools are per-user AND carry the caller's `Authentication`.** `DocumentAiToolsFactory.create(chatModel, userEmail, authentication)`; inside the tools every blocking service call goes through `blockWithAuth(...)` which re-establishes the Authentication in the Reactor context — without it, secure DAO overrides in extension layers would see no user on tool threads. `writeFile` goes through `documentService.uploadDocument` (the full upload pipeline: ownership, audit, checksum, indexing), never a raw repository save.
- **RAG retrieval fails closed.** `AiChatServiceImpl.retrieveContext` filters similarity-search chunks by `AiAccessPolicy.canRead(document_id, userEmail)` when the policy is not permit-all; chunks without `document_id` metadata are dropped. Pinned by `AiRagAccessFilterTest`.
- **`AiDocumentQueryService` is user-aware**: `query(request, userEmail)` / `count(request, userEmail)` with protected `appendFromClause`/`bindUserContext` hooks (core: bare FROM, no binding). The enterprise layer overrides them to add the `doc_owner`/`doc_share` joins its `defaultListFolderCriteria` bean references via `:usrId`.

### AI end-to-end tests against a real LLM

`AiRealLlmE2EIT` runs the AI feature against a real model in an Ollama testcontainer — no mocked `ChatModel`/`EmbeddingModel` — covering real embeddings, pgvector similarity search, the streaming chat pipeline through Spring AI's advisor chain, conversation persistence, and real tool dispatch (`createFolder`, `queryDocuments`, `readDocumentContent`). The mock-based `AiChatControllerIT`/`DocumentAiToolsIT` stay as they are: they pin plumbing and tool behaviour cheaply, and they'd never catch an advisor-chain or tool-dispatch regression.

- **Models are chosen for size, not quality.** `qwen2.5:1.5b` (~1 GB) is the lightest model measured to drive the `@Tool` methods reliably — 6/6 correct tool calls on a folder-listing prompt, where `qwen2.5:0.5b` managed 1/5 and 7B managed 5/5 for 4.7 GB. `nomic-embed-text` (~270 MB) gives the 768-dim vectors `AiConfig` configures `PgVectorStore` with.
- **The container runs CPU-only.** `OllamaContainer` requests every available GPU by default, which hard-fails on a host without an NVIDIA runtime (CI runners, Docker Desktop on WSL2: `nvidia-container-cli: initialization error`). `SharedOllamaContainer` clears the device requests.
- **Models are pulled once.** The first run pulls ~1.3 GB and commits the populated container to a local image; later runs start from it (~270s → ~110s). Delete `openfilz/tc-ollama-qwen2.5-1.5b:1` to refresh.
- **Assert on side effects, not wording.** Embeddings are deterministic so those assertions are exact; generation is not, so the tool tests check that the folder really exists afterwards and retry through `eventually(...)` — a single unlucky sampling isn't a defect, three in a row is. Chat temperature is pinned to 0.
- **`@DynamicPropertySource` can't drive the provider selectors.** `AiModelProviderEnvironmentPostProcessor` runs during `prepareEnvironment`, before those values exist, so it would read `openfilz.ai.active` as absent and pin everything to `none`, leaving no `ChatModel`. The test sets `spring.ai.model.chat`/`.embedding` explicitly — the documented override.

### MCP server (external AI agents)

`openfilz.mcp.active=true` exposes the **same `@Tool` methods the AI assistant uses** to
external agents (Claude Code/Desktop, n8n, custom agents, Spring AI clients) over
`POST /mcp`, via `spring-ai-starter-mcp-server-webflux`.

- **The MCP layer never defines a tool** — `McpToolCallbackProvider` only adapts the
  `ToolCallback`s harvested from the registered `McpToolContributor` beans. Any capability added
  to a tool object is gained by both the chat assistant and every external agent at once.
- **New tools arrive as an `McpToolContributor`, not by editing the provider.** A contributor
  supplies `bind(userEmail, authentication)` (a `@Tool` object, or `bind(null,null)` for the
  definitions template — which must resolve no `ChatModel`, see the cycle note below) and a
  `name → ToolCapability` map. `DocumentAiToolsContributor` is the core one; the provider merges
  all contributors and applies auth + role + read-only enforcement uniformly, so a new contributor
  inherits every guarantee. This is the seam the EE `CollaborationMcpToolContributor` (share /
  comment tools) plugs into with no core change.
- **Stateless transport** (`spring.ai.mcp.server.protocol=STATELESS`): every request carries
  its own bearer token, so no MCP session outlives the JWT that opened it and scaling needs
  no sticky sessions. SSE is deprecated since Spring AI 2.0.0.
- **Per-call user binding.** `McpAuthenticationWebFilter` parks the already-validated
  `Authentication` in the exchange attributes; `McpConfig`'s `contextExtractor` forwards it
  into the `McpTransportContext`; the tool callback reads it back from
  `ToolContext.getContext().get("exchange")` and builds a fresh user-bound `DocumentAiTools`
  through `DocumentAiToolsFactory`. Identity is never read from tool arguments, and a call
  without it is refused rather than run unbound.
- **OAuth 2.1 discovery** (`McpDiscoveryController`): `/.well-known/oauth-protected-resource`
  (RFC 9728) names `/mcp` + the Keycloak realm as its authorization server; the `/mcp` 401 carries
  `WWW-Authenticate: … resource_metadata="…"` so remote hosts (Claude Desktop, claude.ai, IDE
  connectors) discover it. `/.well-known/oauth-authorization-server` 302-redirects to Keycloak's
  own OIDC document. Both whitelisted, both 404 when `openfilz.mcp.active=false`. A shared public
  PKCE client `openfilz-mcp` (in both realm-exports) is what hosts authenticate with — DCR is
  deliberately off (one client, not one per connecting app); its token carries `realm_access.roles`
  so role enforcement applies. `authorization-server-url` defaults to `KEYCLOAK_REALM_URL`.
- **`/mcp` is JWT-protected simply by not being whitelisted** (`DefaultAuthSecurityConfig` ends
  with `anyExchange().authenticated()`) — but that chain performs **no role check** on it: its
  `.access(...)` manager is scoped to `/api/v1/**` + the GraphQL path. Roles are therefore enforced
  in the **tool layer** instead, and must be: without it a READER-only token was refused
  `POST /api/v1/folders` with 403 while `tools/call createFolder` created the folder (confirmed
  against a live server). Tools call `DocumentService` in-process on a tool thread, so no request
  is ever matched by the security chain.
- **Two independent gates, both must pass.** `AiToolRolePolicy` (+ `ToolCapability`) answers *may
  this caller perform this kind of operation* — mirroring `AbstractSecurityService`, including its
  `hasAllRoles` cases (e-Sign needs CONTRIBUTOR **and** SIGN_REQUESTER; EE share writes need
  CONTRIBUTOR **and** EDIT_SHARE). `AiAccessPolicy` answers *which documents*. The gate lives in
  `DocumentAiTools`, so the **chat assistant is covered too** — it had the same hole, since
  `/api/v1/ai/**` admits READER and the tools checked nothing. `McpToolCallbackProvider` refuses
  earlier via `TOOL_CAPABILITIES` and fails closed on an unclassified tool.
- **`ToolRoleParityWithRestTest` is the anti-drift guard**: it drives both the tool policy and the
  REST `SecurityService.authorize(...)` over every role set and fails if they disagree. Change a
  REST role and it fails until the tool mapping follows.
- **`SecurityService` is injected as `Optional`** — the bean is `@Conditional` and absent under
  `openfilz.security.no-auth=true`. Absent means "authorization is off here", not "no roles";
  requiring it broke every no-auth deployment's tools.
- **Read-only by default** (`openfilz.mcp.mode`, `READ_ONLY` | `READ_WRITE`): an autonomous
  agent mutating a DMS is an explicit opt-in. Mutating tools are withheld from `tools/list`.
- **`@ToolParam(required = false)` matters here in a way it never did for chat.** Spring AI
  defaults `required` to `true`, and the MCP server validates arguments against the generated
  JSON Schema *before* the tool body runs — so an unmarked optional parameter makes the tool
  effectively uncallable (`null trouvé, string attendu`). Mark every optional parameter.
- **`DocumentAiTools.chatModel` is nullable**: an MCP deployment need not run an LLM of its
  own. Only `describeImage` uses it, and it degrades with a message. The class therefore
  declares its constructor explicitly — two candidate constructors (Lombok's plus one written
  out) leave Spring looking for a default one.
- **Never resolve a `ChatModel` while building the tool *definitions*.** Spring AI calls
  `ToolCallbackProvider.getToolCallbacks()` from `toolCallbackResolver` **and** `syncTools`,
  both while those beans are still being created. `McpToolCallbackProvider.templateCallbacks()`
  therefore passes a `null` chat model: asking the `ObjectProvider` for one there instantiates it
  mid-refresh and closes a cycle — `toolCallbackResolver → getToolCallbacks() → ollamaChatModel →
  toolCallingManager → toolCallbackResolver` — which aborts startup with *"dependencies of some
  of the beans form a cycle"*. Definitions are static; only `describeImage`'s *execution* needs a
  model, and that happens on the call path long after startup. It bites hardest in the EE native
  image (AOT bakes the ChatModel bean definition in whatever `openfilz.ai.active` says at runtime),
  but any deployment running the chat assistant and MCP together hits it.
- **Native image:** `spring-ai-mcp` registers the `McpSchema` inner classes itself via
  `META-INF/spring/aot.factories`; `McpRuntimeHints` only covers the ServiceLoader-resolved
  JSON layer. `McpRuntimeHintsTest` fails if that upstream registration ever disappears.
  Core is JVM-only, but these classes are compiled into the enterprise native image. A GraalVM
  tracing-agent run (`openfilz-enterprise/docker/trace-mcp-native-hints.sh`) confirmed the residue
  is exactly those two ServiceLoader SPI files — **no further reflection hints are needed**.
- **Tool surface (core):** 17 document tools in `DocumentAiTools` — whoami (identity + effective
  per-capability permissions, capability `IDENTITY_READ` = any authenticated caller via
  `RoleRequirement.authenticated()`), query/read/path/vision, write/
  create/move/rename, metadata get/search/update/delete, delete (CLEANER), version list/restore,
  downloadDocument (text inline or a REST download link for binary; shares `extractText` with
  readDocumentContent).
  Each is classified in `DocumentAiToolsContributor.CAPABILITIES` (a `Map.ofEntries`, since >10)
  and driven by `McpProtocolIT.everyAdvertisedToolIsCallable`, which now authenticates as
  `admin-user` (holds CONTRIBUTOR+CLEANER+AUDITOR+SIGN_REQUESTER) so every tool — including the
  CLEANER-gated `deleteDocument` — actually dispatches for the layer-2 trace.
- **Tests:** `McpRuntimeHintsTest` (hints), `McpProtocolIT` (read-write, full protocol),
  `McpReadOnlyModeIT` (default posture), `McpWithChatModelIT` (MCP + a real `ChatModel`),
  `DefaultAiToolRolePolicyTest` + `ToolRoleParityWithRestTest` + `McpRoleEnforcementIT` (roles),
  sharing `AbstractMcpIT`'s JSON-RPC client.
  `McpProtocolIT.everyAdvertisedToolIsCallable` deliberately drives *every* tool and asserts
  `isError == false` — it doubles as the trace driver for deriving native-image metadata.
- **The `spring.ai.model.*` selectors are registered per-suite, not in `AbstractMcpIT`.** A
  superclass `@DynamicPropertySource` registration **wins** over a subclass one, so a suite that
  "overrides" `spring.ai.model.chat` silently keeps the parent's value and tests nothing. Each
  suite calls `registerModelSelectors(registry, chat)` explicitly; `McpWithChatModelIT` asserts a
  `ChatModel` bean is really present so it can never go vacuous again.
- **Run them:** `-Dtest=none` also needs `-Dsurefire.failIfNoSpecifiedTests=false` on surefire
  3.5.3, or the build fails before failsafe starts.

### Release / publish CI
The TypeScript SDK (`@openfilz-sdk/typescript`) publishes to npm via **OIDC Trusted Publishing** (no `NPM_TOKEN`): the publish job needs `permissions: id-token: write` (plus `contents: write` / `packages: write`, since declaring permissions zeroes the rest), Node 24 (whose **bundled** npm 11.16+ already satisfies OIDC auto-detection's npm ≥ 11.5.1 requirement), and no `_authToken` line in `.npmrc`.
- **Do NOT `npm install -g npm@latest` in the release workflow.** The self-upgrade currently ships a broken tree whose bundled `sigstore` is missing, so `npm publish` (which auto-enables provenance under OIDC) dies with `npm error Cannot find module 'sigstore'` … `libnpmpublish/lib/provenance.js` (npm/cli#9722). Rely on the pristine Node-bundled npm — it has `sigstore` intact and is already ≥ 11.5.1. Removed the upgrade step from `release-backend.yml` 2026-07-09. The npmjs Trusted Publisher config must match the repo + workflow **filename** exactly — renaming the release workflow file requires updating it on npmjs too. The Python (twine) and C# (NuGet) SDKs still publish via API tokens.
- `npm publish` failing with **`E404 ... PUT ... Not found`** is misleading: a 404 on PUT during publish is npm's disguised "not authorized" — it almost always means an expired/invalid/under-scoped credential, NOT a missing package.

### Local Setup
```bash
cd deploy/docker-compose && docker-compose -f docker-compose.minio.yml up -d minio   # Start MinIO
cd deploy/docker-compose && make up-auth                                              # All dev services with auth
```

**Prerequisites:** Java 25+, Maven 3.x, Docker (for MinIO & integration tests)
