# OpenFilz Docker Deployment

This directory contains Docker Compose configurations for running OpenFilz with different feature combinations.

## Quick Start

```bash
# Copy environment file
cp .env.example .env

# Start ALL services (auth, storage, search, document editing, thumbnails)
make up-full

# Access the application
# - Web UI: http://localhost:4200
# - API: http://localhost:8081
# - Swagger: http://localhost:8081/swagger-ui.html
# - Keycloak: http://localhost:8180
```

To start only the base services (no auth, no extras):

```bash
make up
```

## Architecture

See the [architecture diagram](../README.md#architecture) in the main deploy README.

## Compose Files

| File | Description |
|------|-------------|
| `docker-compose.yml` | Base services: PostgreSQL, API, Web |
| `docker-compose.auth.yml` | Keycloak authentication server |
| `docker-compose.minio.yml` | MinIO S3-compatible storage |
| `docker-compose.onlyoffice.yml` | OnlyOffice document server |
| `docker-compose.fulltext.yml` | OpenSearch full-text search |
| `docker-compose-thumbnails.yml` | Gotenberg for thumbnail generation (PDF, Office documents) |
| `docker-compose-gotenberg-dev.yml` | Gotenberg standalone for local development (IDE + npm workflow) |

---

## Using Makefile (Recommended)

The Makefile automatically generates the frontend configuration (`ngx-env.js`) based on the selected features.

### Basic Commands

```bash
# Start base services (no auth, local storage)
make up

# Stop all services
make down

# Restart all services
make restart

# View logs
make logs

# Show running containers
make ps

# Stop and remove volumes (clean reset)
make clean

# Show all available commands
make help
```

### Feature-Specific Commands

```bash
# Start with Keycloak authentication
make up-auth

# Start with MinIO S3 storage
make up-minio

# Start with OnlyOffice document editing
make up-onlyoffice

# Start with OpenSearch full-text search
make up-fulltext

# Start all CE features (no auth) for demo
make up-demo

# Start ALL services (auth, MinIO, OnlyOffice, OpenSearch, thumbnails)
make up-full
```

### Combination Commands

```bash
# Auth + MinIO
make up-auth-minio

# Auth + OpenSearch
make up-auth-fulltext

# Auth + OnlyOffice
make up-auth-onlyoffice
```

### Utility Commands

```bash
# Build images locally
make build

# Pull latest images
make pull

# Generate ngx-env.js only (useful for debugging)
make generate-config
```

---

## Using Docker Compose Directly

If you prefer not to use Make, you can run Docker Compose commands directly.

### Base Services Only

```bash
docker-compose -f docker-compose.yml up -d
```

### With Authentication

```bash
docker-compose -f docker-compose.yml -f docker-compose.auth.yml up -d
```

### With MinIO Storage

```bash
docker-compose -f docker-compose.yml -f docker-compose.minio.yml up -d
```

### With OnlyOffice

```bash
docker-compose -f docker-compose.yml -f docker-compose.onlyoffice.yml up -d
```

### With Full-Text Search

```bash
docker-compose -f docker-compose.yml -f docker-compose.fulltext.yml up -d
```

### With Thumbnails

```bash
docker-compose -f docker-compose.yml -f docker-compose-thumbnails.yml --profile thumbnails up -d
```

### Full Stack (All Features)

```bash
docker-compose -f docker-compose.yml \
  -f docker-compose.auth.yml \
  -f docker-compose.minio.yml \
  -f docker-compose.onlyoffice.yml \
  -f docker-compose.fulltext.yml \
  -f docker-compose-thumbnails.yml --profile thumbnails up -d
```

### Gotenberg for Local Development

When developing locally with the API running in your IDE and the web app via npm, you can start only Gotenberg for thumbnail support:

```bash
docker-compose -f docker-compose-gotenberg-dev.yml up -d
```

### Important Note for Direct Usage

When not using the Makefile, you must manually generate `ngx-env.js`:

```bash
# Set environment variables and generate config
export NG_APP_API_URL="http://localhost:8081/api/v1"
export NG_APP_GRAPHQL_URL="http://localhost:8081/graphql/v1"
export NG_APP_AUTHENTICATION_ENABLED="true"  # or "false"
export NG_APP_AUTHENTICATION_AUTHORITY="http://localhost:8180/realms/openfilz"
export NG_APP_AUTHENTICATION_CLIENT_ID="openfilz-web"
export NG_APP_ONLYOFFICE_ENABLED="false"  # or "true"

envsubst < ngx-env.template.js > ngx-env.js
```

---

## Environment Variables

Copy `.env.example` to `.env` and customize as needed:

```bash
cp .env.example .env
```

### Database Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `POSTGRES_IMAGE` | `postgres:17` | PostgreSQL Docker image |
| `DB_NAME` | `dms_db` | PostgreSQL database name |
| `DB_USER` | `dms_user` | PostgreSQL username |
| `DB_PASSWORD` | `dms_password` | PostgreSQL password |
| `DB_PORT` | `5432` | PostgreSQL exposed port |

### API Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENFILZ_API_IMAGE` | `ghcr.io/openfilz/openfilz-api:latest` | Docker image for the API |
| `API_PORT` | `8081` | API exposed port |
| `JAVA_OPTS` | `-Xmx512m -Xms256m` | JVM memory options |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200,http://localhost:80` | Allowed CORS origins |
| `OPENFILZ_INTERNAL_API_BASE_URL` | `http://openfilz-api:8081` | API route for internal calls (inter-pods) |
| `OPENFILZ_PUBLIC_API_BASE_URL` | `http://localhost:8081` | API route for external calls |
| `OPENFILZ_SOFTDELETE_ACTIVE` | `true` | Soft Delete feature activation |
| `OPENFILZ_CALCULATECHECKSUM` | `false` | Enable SHA-256 checksum calculation on upload |

### Web Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENFILZ_WEB_IMAGE` | `ghcr.io/openfilz/openfilz-web:latest` | Docker image for the Web UI |
| `WEB_PORT` | `4200` | Web UI exposed port |

### Frontend Configuration

These variables are used to generate `ngx-env.js` for the Angular frontend:

| Variable | Default | Description |
|----------|---------|-------------|
| `NG_APP_API_URL` | `http://localhost:8081/api/v1` | REST API base URL |
| `NG_APP_GRAPHQL_URL` | `http://localhost:8081/graphql/v1` | GraphQL endpoint URL |
| `NG_APP_AUTHENTICATION_ENABLED` | `false` | Enable/disable authentication |
| `NG_APP_AUTHENTICATION_AUTHORITY` | `http://localhost:8180/realms/openfilz` | Keycloak realm URL |
| `NG_APP_AUTHENTICATION_CLIENT_ID` | `openfilz-web` | Keycloak client ID |
| `NG_APP_ONLYOFFICE_ENABLED` | `false` | Enable/disable OnlyOffice integration |

### Storage Configuration (MinIO)

Used with `docker-compose.minio.yml`:

| Variable | Default | Description |
|----------|---------|-------------|
| `STORAGE_TYPE` | `local` | Storage backend: `local` or `minio` |
| `MINIO_ENDPOINT` | `http://minio:9000` | MinIO server endpoint (internal) |
| `MINIO_ACCESS_KEY` | `minioadmin` | MinIO access key |
| `MINIO_SECRET_KEY` | `minioadmin` | MinIO secret key |
| `MINIO_BUCKET` | `openfilz-bucket` | MinIO bucket name |
| `MINIO_PORT` | `9000` | MinIO API exposed port |
| `MINIO_CONSOLE_PORT` | `9001` | MinIO console exposed port |

### Security Configuration (Keycloak)

Used with `docker-compose.auth.yml`:

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENFILZ_SECURITY_NO_AUTH` | `true` | Disable authentication (dev only) |
| `KEYCLOAK_ADMIN` | `admin` | Keycloak admin username |
| `KEYCLOAK_ADMIN_PASSWORD` | `admin` | Keycloak admin password |
| `KEYCLOAK_PORT` | `8180` | Keycloak exposed port |
| `KEYCLOAK_IMAGE` | `ghcr.io/openfilz/keycloak:26.5` | Custom Keycloak Docker image |
| `KEYCLOAK_MANAGEMENT_PORT` | `9000` | Keycloak management port |
| `KEYCLOAK_REALM_URL` | `http://keycloak:8080/realms/openfilz` | Internal Docker URL for Keycloak realm (used by API for JWK fetching) |
| `KEYCLOAK_DB_USER` | `keycloak` | Keycloak database user |
| `KEYCLOAK_DB_PASSWORD` | `keycloak` | Keycloak database password |
| `KEYCLOAK_DB_NAME` | `keycloak_db` | Keycloak database name |
| `KEYCLOAK_PUBLIC_URL` | `http://localhost:8180` | Public URL for Keycloak (used as frontendUrl in realm import) |
| `KEYCLOAK_CLIENT_ROOT_URL` | `http://localhost:4200` | Root URL for the openfilz-web client (used in realm import for `rootUrl`, `redirectUris`, etc.) |
| `KEYCLOAK_DEFAULT_ROLE_1` | `READER` | 1st default role assigned to new users |
| `KEYCLOAK_DEFAULT_ROLE_2` | `READER` | 2nd default role (set to an already-used role if unused) |
| `KEYCLOAK_DEFAULT_ROLE_3` | `READER` | 3rd default role (set to an already-used role if unused) |
| `KEYCLOAK_DEFAULT_ROLE_4` | `READER` | 4th default role (set to an already-used role if unused) |
| `KEYCLOAK_DEFAULT_GROUP_1` | `/OPENFILZ/READER` | 1st default group assigned to new users |
| `KEYCLOAK_DEFAULT_GROUP_2` | `/OPENFILZ/READER` | 2nd default group (set to an already-used group if unused) |
| `KEYCLOAK_DEFAULT_GROUP_3` | `/OPENFILZ/READER` | 3rd default group (set to an already-used group if unused) |
| `KEYCLOAK_DEFAULT_GROUP_4` | `/OPENFILZ/READER` | 4th default group (set to an already-used group if unused) |

> **Note**: Up to 4 default roles and 4 default groups can be assigned to new users. Available roles: `READER`, `CONTRIBUTOR`, `AUDITOR`, `CLEANER`. Available groups: `/OPENFILZ/READER`, `/OPENFILZ/CONTRIBUTOR`, `/OPENFILZ/AUDITOR`, `/OPENFILZ/CLEANER`. If you need fewer than 4, set unused slots to an already-used value — duplicates are ignored by Keycloak. For example, to grant all permissions:
> ```
> KEYCLOAK_DEFAULT_ROLE_1=CONTRIBUTOR
> KEYCLOAK_DEFAULT_ROLE_2=AUDITOR
> KEYCLOAK_DEFAULT_ROLE_3=CLEANER
> KEYCLOAK_DEFAULT_ROLE_4=READER
> KEYCLOAK_DEFAULT_GROUP_1=/OPENFILZ/CONTRIBUTOR
> KEYCLOAK_DEFAULT_GROUP_2=/OPENFILZ/AUDITOR
> KEYCLOAK_DEFAULT_GROUP_3=/OPENFILZ/CLEANER
> KEYCLOAK_DEFAULT_GROUP_4=/OPENFILZ/READER
> ```

> **Note**: The API uses `KEYCLOAK_REALM_URL` with internal Docker DNS (`keycloak:8080`) for JWT validation, while the frontend uses the public URL (`localhost:8180`) for browser authentication. This avoids network issues where the API container cannot reach `localhost`.

#### Keycloak Database Auto-Initialization

The Keycloak database and user are automatically created on the first PostgreSQL startup via the `init-keycloak-db.sh` script mounted into `/docker-entrypoint-initdb.d/`. The script uses the `KEYCLOAK_DB_USER`, `KEYCLOAK_DB_PASSWORD`, and `KEYCLOAK_DB_NAME` environment variables passed to the postgres service.

> **Note**: This only runs when the PostgreSQL data volume is empty (first startup). If you already have a running database, create the Keycloak database manually.

### SMTP Configuration (Keycloak Emails)

Used with `docker-compose.auth.yml` for Keycloak email sending (password reset, email verification, etc.):

| Variable | Default | Description |
|----------|---------|-------------|
| `SMTP_HOST` | *(empty)* | SMTP server hostname (leave empty to disable) |
| `SMTP_PORT` | `587` | SMTP server port |
| `SMTP_FROM` | *(empty)* | Sender email address |
| `SMTP_SSL` | `false` | Enable SSL |
| `SMTP_STARTTLS` | `true` | Enable STARTTLS |
| `SMTP_AUTH` | `true` | Enable SMTP authentication |
| `SMTP_USER` | *(empty)* | SMTP username |
| `SMTP_PASSWORD` | *(empty)* | SMTP password |

### Identity Providers / Social Login

Used with `docker-compose.auth.yml` for Keycloak social login. Leave empty to disable a provider:

| Variable | Default | Description |
|----------|---------|-------------|
| `GOOGLE_CLIENT_ID` | *(empty)* | Google OAuth2 client ID |
| `GOOGLE_CLIENT_SECRET` | *(empty)* | Google OAuth2 client secret |
| `GITHUB_CLIENT_ID` | *(empty)* | GitHub OAuth2 client ID |
| `GITHUB_CLIENT_SECRET` | *(empty)* | GitHub OAuth2 client secret |
| `MICROSOFT_CLIENT_ID` | *(empty)* | Microsoft OAuth2 client ID |
| `MICROSOFT_CLIENT_SECRET` | *(empty)* | Microsoft OAuth2 client secret |

### OnlyOffice Configuration

Used with `docker-compose.onlyoffice.yml`:

| Variable | Default | Description |
|----------|---------|-------------|
| `ONLYOFFICE_ENABLED` | `false` | Enable OnlyOffice integration |
| `ONLYOFFICE_PORT` | `8080` | OnlyOffice exposed port |
| `ONLYOFFICE_URL` | `http://localhost:8080` | OnlyOffice URL |
| `ONLYOFFICE_JWT_SECRET` | `openfilz-onlyoffice-jwt-secret-2024` | JWT secret for OnlyOffice |

### Full-Text Search Configuration (OpenSearch)

Used with `docker-compose.fulltext.yml`:

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENFILZ_FULLTEXT_ACTIVE` | `false` | Enable full-text search |
| `OPENSEARCH_PORT` | `9200` | OpenSearch API exposed port |
| `OPENSEARCH_PERF_PORT` | `9600` | OpenSearch performance port |
| `OPENSEARCH_DASHBOARDS_PORT` | `5601` | OpenSearch Dashboards exposed port |

### Thumbnail Configuration (Gotenberg)

Used with `docker-compose-thumbnails.yml`. Gotenberg converts PDF and Office documents (DOCX, XLSX, PPTX, ODT, ODS, ODP) to generate thumbnails.

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENFILZ_THUMBNAIL_ACTIVE` | `true` | Enable thumbnail generation |
| `OPENFILZ_THUMBNAIL_API_BASE_URL` | `http://openfilz-api:8081` | API base URL for thumbnail callbacks |
| `GOTENBERG_URL` | `http://gotenberg:3000` | Gotenberg server URL (internal) |
| `GOTENBERG_PORT` | `8083` | Gotenberg exposed port |

> **Note**: Thumbnails use a Docker Compose profile. When using Docker Compose directly, add `--profile thumbnails` to include the Gotenberg service. The Makefile handles this automatically for `up-demo` and `up-full`.

---

## Service URLs

After starting services, access them at:

| Service | URL | Notes |
|---------|-----|-------|
| Web UI | http://localhost:4200 | Main application |
| API | http://localhost:8081 | REST API |
| Swagger | http://localhost:8081/swagger-ui.html | API documentation |
| GraphQL | http://localhost:8081/graphql/v1 | GraphQL endpoint |
| Keycloak | http://localhost:8180 | Auth admin (with `make up-auth`) |
| MinIO Console | http://localhost:9001 | Storage admin (with `make up-minio`) |
| OnlyOffice | http://localhost:8080 | Document server (with `make up-onlyoffice`) |
| OpenSearch Dashboards | http://localhost:5601 | Search analytics (with `make up-fulltext`) |
| Gotenberg | http://localhost:8083 | Thumbnail service (with `make up-demo` or `make up-full`) |

---

## Common Scenarios

### Development (No Auth)

```bash
make up
```

### Development with Auth Testing

```bash
make up-auth
```

### Demo (All CE Features, No Auth)

```bash
make up-demo
```

This starts all Community Edition features (OnlyOffice, OpenSearch, thumbnails, checksum) without authentication, ideal for demos and evaluations.

### Production-like Setup

```bash
# Edit .env with production values
vi .env

# Start full stack
make up-full
```

### Switching Configurations

```bash
# Stop current setup
make down

# Start with different features
make up-auth-minio
```

### Local Development with Thumbnails

When running the API in your IDE and the web app via `npm start`:

```bash
# Start only Gotenberg for thumbnail support
docker-compose -f docker-compose-gotenberg-dev.yml up -d
```

Then configure your API with `GOTENBERG_URL=http://localhost:8083` (Gotenberg listens on port 3000 internally, mapped to 8083 externally).

---

## Troubleshooting

### View Logs

```bash
# All services
make logs

# Specific service
docker-compose logs -f openfilz-api
```

### Reset Everything

```bash
# Stop and remove all containers and volumes
make clean

# Start fresh
make up
```

### Check Service Health

```bash
# API health
curl http://localhost:8081/actuator/health

# Database connection
docker exec openfilz-postgres pg_isready -U dms_user -d dms_db
```

### Frontend Not Updating

If frontend configuration seems stale, regenerate it:

```bash
make clean
make up-auth  # or your desired configuration
```

### Debug Frontend Config

To inspect the generated `ngx-env.js` without starting services:

```bash
make generate-config
```
