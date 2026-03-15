# OpenFilz Contributor Guide

This guide is for developers who want to **contribute** to the OpenFilz open-source project. It covers the codebase architecture, development setup, coding patterns, and how to add new features.

---

## Table of Contents

- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Clone and Build](#clone-and-build)
  - [Run Locally](#run-locally)
  - [Run Tests](#run-tests)
- [Project Structure](#project-structure)
- [Architecture](#architecture)
  - [Layered Architecture](#layered-architecture)
  - [Reactive Programming](#reactive-programming)
  - [Storage Abstraction](#storage-abstraction)
  - [Security Model](#security-model)
  - [Database Layer](#database-layer)
- [Design Patterns](#design-patterns)
- [How to Add a REST Endpoint](#how-to-add-a-rest-endpoint)
- [How to Add a GraphQL Query](#how-to-add-a-graphql-query)
- [How to Add a Storage Backend](#how-to-add-a-storage-backend)
- [How to Modify Security](#how-to-modify-security)
- [Testing](#testing)
- [SDK Generation](#sdk-generation)
- [Docker Image Build](#docker-image-build)
- [Code Conventions](#code-conventions)
- [Pull Request Guidelines](#pull-request-guidelines)

---

## Getting Started

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java | 25+ | Required |
| Maven | 3.x | Required |
| Docker | 20+ | For integration tests (Testcontainers) and optional services |
| Node.js | 18+ | Only if building the TypeScript SDK |
| Python | 3.8+ | Only if building the Python SDK |
| .NET SDK | 8.0+ | Only if building the C# SDK |

### Clone and Build

```bash
git clone https://github.com/openfilz/openfilz-core.git
cd openfilz-core

# Build all modules
mvn clean install

# Build only the API module (faster)
mvn clean install -pl openfilz-api -am
```

### Run Locally

**Option 1: API only (minimal setup)**

```bash
# Start PostgreSQL and optional services via Docker Compose
cd deploy/docker-compose
cp .env.example .env
make up   # starts PostgreSQL, you can also use make up-auth, make up-minio, etc.

# Run the API
cd ../../openfilz-api
mvn spring-boot:run
```

The API starts on `http://localhost:8081`. Swagger UI is at `http://localhost:8081/swagger-ui.html`.

**Option 2: Full stack via Docker Compose**

```bash
cd deploy/docker-compose
cp .env.example .env
make up-full   # starts everything: PostgreSQL, Keycloak, MinIO, OnlyOffice, OpenSearch, thumbnails
```

### Run Tests

```bash
# All tests (Testcontainers auto-starts PostgreSQL and Keycloak)
mvn test

# Tests for API module only
mvn test -pl openfilz-api
```

Integration tests use **Testcontainers** — Docker must be running. PostgreSQL and Keycloak containers are started automatically.

> **Warning: Do NOT use `mvn clean install -DskipTests` when building the full project.**
>
> The SDK modules (`openfilz-sdk-java`, `openfilz-sdk-java-reactive`, etc.) depend on the OpenAPI spec artifact (`openfilz-api:openapi:json`) that is generated during the `post-integration-test` phase and attached by the `openapi-spec` Maven profile. This profile is **only activated when `-DskipTests` is NOT set**. Using `-DskipTests` prevents the spec from being generated, causing the SDK builds to fail with: `Could not find artifact org.openfilz:openfilz-api:jar:openapi`.
>
> **If you need to skip tests**, use one of these approaches:
>
> ```bash
> # Option 1: Build only the API module (no SDKs) — safe to skip tests
> mvn clean install -DskipTests -pl openfilz-api -am
>
> # Option 2: Build everything including SDKs — force the openapi-spec profile
> mvn clean install -DskipTests -Popenapi-spec
> ```

---

## Project Structure

```
openfilz-core/
├── openfilz-api/                  # Core DMS service (REST + GraphQL)
│   ├── src/main/java/org/openfilz/dms/
│   │   ├── config/                # Spring configurations
│   │   ├── controller/            # REST & GraphQL controllers
│   │   ├── service/               # Business logic (interfaces + impl)
│   │   ├── repository/            # R2DBC repositories + custom DAOs
│   │   ├── entity/                # Database entities
│   │   ├── dto/                   # Request/Response DTOs
│   │   ├── enums/                 # Role, DocumentType, AuditAction, etc.
│   │   ├── exception/             # Custom exceptions
│   │   ├── mapper/                # MapStruct DTO mappers
│   │   ├── security/              # Security services (authorization)
│   │   └── utils/                 # Helpers (JwtTokenParser, etc.)
│   └── src/main/resources/
│       ├── application.yml        # Base configuration
│       ├── application-dev.yml    # Dev overrides
│       ├── graphql/               # GraphQL schema files (.graphqls)
│       └── db/migration/          # Flyway SQL migrations
├── openfilz-sdk/                  # SDK generation (5 languages)
│   ├── openfilz-sdk-java/
│   ├── openfilz-sdk-java-reactive/
│   ├── openfilz-sdk-typescript/
│   ├── openfilz-sdk-python/
│   ├── openfilz-sdk-csharp/
│   └── openfilz-sdk-samples-test/ # SDK integration tests
├── deploy/
│   ├── docker-compose/            # Docker Compose configs + Makefile
│   │   └── dokploy/               # Dokploy deployment
│   └── helm/                      # Kubernetes Helm charts
├── CLAUDE.md                      # Architecture reference
├── CHANGELOG.md                   # Release history
├── Roadmap.md                     # Product roadmap
└── pom.xml                        # Parent POM
```

---

## Architecture

### Layered Architecture

```
Controller (REST/GraphQL)
    ↓
Service (business logic)
    ↓
DAO / Repository (data access)
    ↓
PostgreSQL (metadata) + Storage (files)
```

- **Controllers** handle HTTP requests and delegate to services
- **Services** contain business logic, validation, and orchestration
- **DAOs** provide complex queries (recursive CTEs, joins); Repositories provide simple CRUD
- **Storage** is abstracted behind the `StorageService` interface

### Reactive Programming

The entire stack is reactive and non-blocking:

| Layer | Technology |
|-------|-----------|
| HTTP | Spring WebFlux (Netty) |
| Database | R2DBC (reactive PostgreSQL driver) |
| Reactor | `Mono<T>` (single value), `Flux<T>` (stream) |

**Key reactive patterns used in the codebase:**

```java
// Composition with flatMap
return documentRepository.findById(id)
    .flatMap(doc -> storageService.loadFile(doc.getStoragePath())
        .map(resource -> new DownloadResult(doc, resource)));

// Sequential operations with flatMapSequential
return Flux.fromIterable(folderIds)
    .flatMapSequential(id -> deleteFolderRecursive(id));

// Blocking I/O on the elastic scheduler
return Mono.fromCallable(() -> minioClient.putObject(...))
    .subscribeOn(Schedulers.boundedElastic());

// Transactional
@Transactional
public Mono<Document> uploadDocument(...) { ... }
```

**Rules:**
- Never block the event loop — use `subscribeOn(Schedulers.boundedElastic())` for blocking I/O
- Use `Mono`/`Flux` throughout; avoid `.block()`
- Use `@Transactional` on service methods for R2DBC transactions

### Storage Abstraction

The `StorageService` interface defines all storage operations:

```java
public interface StorageService {
    Mono<String> saveFile(FilePart filePart);
    Mono<String> replaceFile(String oldStoragePath, FilePart filePart);
    Mono<Resource> loadFile(String storagePath);
    Mono<Void> deleteFile(String storagePath);
    Mono<String> copyFile(String sourceStoragePath);
    Mono<Long> getFileLength(String storagePath);
}
```

**Implementations:**

| Class | Activated By | Notes |
|-------|-------------|-------|
| `FileSystemStorageService` | `storage.type=local` | Java NIO, path = `{UUID}#{filename}` |
| `MinioStorageService` | `storage.type=minio` | S3 API, piped streams, bucket versioning |

Selected via `@Lazy @Qualifier` + a `@Configuration` factory with `@Primary @Bean` that reads the property at runtime (required for GraalVM native image compatibility — `@ConditionalOnProperty` is build-time only in native images).

### Security Model

**`AbstractSecurityService`** maps HTTP methods to required roles:

| HTTP Method / Path | Required Role |
|--------------------|---------------|
| DELETE | `CLEANER` |
| GET, GraphQL queries, search | `READER` or `CONTRIBUTOR` |
| POST, PUT, PATCH | `CONTRIBUTOR` |
| `/audit/*` | `AUDITOR` |

**Implementations:**

| Class | Mode | Behavior |
|-------|------|----------|
| `SecurityServiceImpl` | Default | Full CRUD authorization |
| `WormSecurityServiceImpl` | WORM | Read-only (no write/delete) |

Roles are extracted from the JWT token by `DefaultJwtTokenParser`, reading either `realm_access.roles` or `groups` claims.

### Database Layer

- **R2DBC** with Spring Data repositories for simple CRUD
- **DocumentDAO** for complex queries: recursive CTEs (folder trees), joins (favorites), pagination, sorting
- **Flyway** manages schema migrations in `src/main/resources/db/migration/`
- **JSONB** columns for flexible metadata storage

**Key entities:**

| Entity | Table | Description |
|--------|-------|-------------|
| `Document` | `documents` | Files and folders |
| `UserFavorite` | `user_favorites` | Per-user favorites (composite PK: email + docId) |
| `RecycleBin` | `recycle_bin` | Soft-deleted items |
| `AuditLog` | `audit_logs` | Immutable operation history with hash chain |

---

## Design Patterns

| Pattern | Usage |
|---------|-------|
| **Strategy** | `StorageService` — multiple implementations for different backends |
| **Template Method** | `AbstractSecurityService` — common authorization logic, override for WORM mode |
| **DAO** | `DocumentDAO` — complex query composition with recursive CTEs |
| **Factory** | `StorageConfig` — runtime switching between storage implementations |
| **MapStruct** | DTO mapping between entities and response objects |

---

## How to Add a REST Endpoint

1. **Create DTOs** in `dto/` for request and response:

```java
public record MyRequest(String name, UUID parentId) {}
public record MyResponse(UUID id, String name) {}
```

2. **Add method to the service interface** in `service/`:

```java
Mono<MyResponse> myOperation(MyRequest request, String userEmail);
```

3. **Implement in the service** in `service/impl/`:

```java
@Override
@Transactional
public Mono<MyResponse> myOperation(MyRequest request, String userEmail) {
    return documentRepository.findById(request.parentId())
        .map(doc -> new MyResponse(doc.getId(), doc.getName()));
}
```

4. **Add controller method** in `controller/`:

```java
@PostMapping("/my-endpoint")
public Mono<MyResponse> myEndpoint(@RequestBody MyRequest request,
                                    @AuthenticationPrincipal Jwt jwt) {
    String email = jwtTokenParser.extractEmail(jwt);
    return myService.myOperation(request, email);
}
```

5. **Write tests** — unit tests for the service, integration tests for the controller.

---

## How to Add a GraphQL Query

1. **Update the schema** in `src/main/resources/graphql/document.graphqls`:

```graphql
type Query {
    # ... existing queries
    myQuery(id: UUID!): MyType
}

type MyType {
    id: UUID!
    name: String!
}
```

2. **Add a resolver** in the GraphQL controller:

```java
@QueryMapping
public Mono<MyType> myQuery(@Argument UUID id) {
    return myService.findById(id);
}
```

3. Custom scalars (`UUID`, `DateTime`, `JSON`, `Long`) are already registered in `GraphQlConfig`.

---

## How to Add a Storage Backend

1. **Implement `StorageService`**:

```java
@Service
@Lazy
@Qualifier("customStorage")
public class CustomStorageService implements StorageService {
    @Override
    public Mono<String> saveFile(FilePart filePart) { ... }
    // ... implement all methods
}
```

2. **Update `StorageConfig`** factory to handle the new type:

```java
@Primary @Bean
public StorageService storageService(@Value("${storage.type}") String type,
                                      @Qualifier("customStorage") StorageService custom,
                                      ...) {
    return switch (type) {
        case "custom" -> custom;
        // ... existing cases
    };
}
```

3. **Add configuration properties** to `application.yml`.

---

## How to Modify Security

1. **Extend `AbstractSecurityService`**:

```java
public class CustomSecurityService extends AbstractSecurityService {
    @Override
    public Mono<Void> authorize(ServerWebExchange exchange) {
        // Custom authorization logic
    }
}
```

2. **Register with a condition** (e.g., a feature toggle):

```java
@Bean
@ConditionalOnProperty(name = "openfilz.security.custom-roles", havingValue = "true")
public SecurityService customSecurityService() {
    return new CustomSecurityService();
}
```

3. **Update `openfilz.security` config** in `application.yml`.

---

## Testing

### Test Stack

| Tool | Purpose |
|------|---------|
| JUnit 5 | Test framework |
| Testcontainers | PostgreSQL and Keycloak containers for integration tests |
| WebTestClient | Reactive HTTP testing |
| StepVerifier | Reactor test utility for verifying Mono/Flux |

### Integration Tests

Integration tests auto-start PostgreSQL and Keycloak containers via Testcontainers. Docker must be running.

```bash
mvn test -pl openfilz-api
```

### Writing Tests

- Place tests in `src/test/java/` mirroring the main source structure
- Use `@SpringBootTest` with `WebTestClient` for controller integration tests
- Use `StepVerifier` for service-level reactive tests
- Test realm config is in `src/test/resources/keycloak/realm-export.json`

---

## SDK Generation

All 5 SDKs are auto-generated from the OpenAPI spec using the OpenAPI Generator Maven plugin.

### Build All SDKs

```bash
mvn clean install -pl openfilz-sdk/openfilz-sdk-java,openfilz-sdk/openfilz-sdk-java-reactive,openfilz-sdk/openfilz-sdk-typescript,openfilz-sdk/openfilz-sdk-python,openfilz-sdk/openfilz-sdk-csharp -am
```

### Build a Single SDK

```bash
mvn clean install -pl openfilz-sdk/openfilz-sdk-typescript -am
```

### Pipeline

1. `initialize` — copies OpenAPI spec JSON from `openfilz-api`
2. `generate-sources` — OpenAPI Generator produces language-specific code
3. `process-sources` — copies GraphQL schemas into the SDK
4. `compile/package` — standard compilation or native toolchain
5. `deploy` — publishes to registry

See `openfilz-sdk/README.md` for full publishing instructions and required credentials.

---

## Docker Image Build

```bash
# Build Docker image using Jib (no Docker daemon required)
mvn clean install -Pkube -pl openfilz-api -am
```

Produces: `localhost:5000/snapshots/openfilz-api:1.0.0-SNAPSHOT`

---

## Code Conventions

### General

- **Reactive everywhere** — all service methods return `Mono<T>` or `Flux<T>`
- **No blocking** — never call `.block()` in production code; use `subscribeOn(Schedulers.boundedElastic())` for blocking I/O
- **DTOs for API boundaries** — entities are internal; controllers work with DTOs
- **MapStruct for mapping** — prefer MapStruct over manual mapping
- **JSONB for metadata** — flexible, queryable, no schema migrations needed
- **Audit everything** — log all write operations via `AuditService`

### Naming

- Packages: `org.openfilz.dms.*`
- Entities: singular (`Document`, `AuditLog`)
- DTOs: `*Request`, `*Response` suffix
- Services: interface + `*Impl`
- Controllers: `*Controller`

### Configuration

- Feature toggles via `@ConditionalOnProperty` or `@ConditionalOnExpression`
- For GraalVM native image support: use `@Lazy @Qualifier` + factory `@Configuration` instead of `@ConditionalOnProperty` for runtime-switchable beans

### Database

- Migrations in `db/migration/V{major}_{minor}__description.sql`
- Always provide forward migrations (no rollbacks needed with Flyway baseline)
- Use `out-of-order: true` to allow non-sequential migration development

---

## Pull Request Guidelines

1. **Create a feature branch** from `develop` (not `main`)
2. **Write tests** — every new feature or bug fix should include tests
3. **Run the full test suite** before submitting: `mvn test`
4. **Keep PRs focused** — one feature or fix per PR
5. **Update the changelog** if the change is user-facing
6. **Follow reactive conventions** — no blocking, proper error handling, transactional where needed

### Release Process

Releases are automated via GitHub Actions (`release-backend.yml`):

1. PR labels (`release:patch`, `release:minor`, `release:major`) determine the version bump
2. `mvn release:prepare` updates POM versions and creates a Git tag
3. `mvn release:perform` builds and deploys all artifacts (API JAR, Docker image, 5 SDKs)
4. CHANGELOG.md is auto-generated from Git history
5. A GitHub Release is created with attached artifacts
6. `main` is merged back into `develop`

---

## License

OpenFilz is licensed under the **Apache License 2.0**. By contributing, you agree that your contributions will be licensed under the same terms.
