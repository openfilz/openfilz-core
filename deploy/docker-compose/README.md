# OpenFilz Docker Deployment

This directory contains Docker Compose configurations for running OpenFilz with different feature combinations.

## Quick Start

```bash
# Copy environment file
cp .env.example .env

# Start base services (recommended for first run)
make up

# Access the application
# - Web UI: http://localhost:4200
# - API: http://localhost:8081
# - Swagger: http://localhost:8081/swagger-ui.html
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

---

## Using Makefile (Recommended)

The Makefile automatically generates the frontend configuration (`ngx-env.js`) based on the selected features.

### Basic Commands

```bash
# Start base services (no auth, local storage)
make up

# Stop all services
make down

# View logs
make logs

# Show running containers
make ps

# Stop and remove volumes (clean reset)
make clean
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

# Start ALL services
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

### Full Stack (All Features)

```bash
docker-compose -f docker-compose.yml \
  -f docker-compose.auth.yml \
  -f docker-compose.minio.yml \
  -f docker-compose.onlyoffice.yml \
  -f docker-compose.fulltext.yml up -d
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
| `DB_NAME` | `dms_db` | PostgreSQL database name |
| `DB_USER` | `dms_user` | PostgreSQL username |
| `DB_PASSWORD` | `dms_password` | PostgreSQL password |
| `DB_PORT` | `5432` | PostgreSQL exposed port |

### API Configuration

| Variable | Default                                     | Description                              |
|----------|---------------------------------------------|------------------------------------------|
| `OPENFILZ_API_IMAGE` | `ghcr.io/openfilz/openfilz-api:latest`      | Docker image for the API                 |
| `API_PORT` | `8081`                                      | API exposed port                         |
| `JAVA_OPTS` | `-Xmx512m -Xms256m`                         | JVM memory options                       |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200,http://localhost:80` | Allowed CORS origins                     |
| `OPENFILZ_INTERNAL_API_BASE_URL` | `http://openfilz-api:8081`                  | API route for internal calls (inter-pods) |
| `OPENFILZ_PUBLIC_API_BASE_URL` | `http://localhost:8081`                     | API route for external calls             |
| `OPENFILZ_SOFT_DELETE` | `true or false`                             | Soft Delete feature activation           |

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

> **Note**: The API uses `KEYCLOAK_REALM_URL` with internal Docker DNS (`keycloak:8080`) for JWT validation, while the frontend uses the public URL (`localhost:8180`) for browser authentication. This avoids network issues where the API container cannot reach `localhost`.

#### Keycloak Database Auto-Initialization

The Keycloak database and user are automatically created on the first PostgreSQL startup via the `init-keycloak-db.sh` script mounted into `/docker-entrypoint-initdb.d/`. The script uses the `KEYCLOAK_DB_USER`, `KEYCLOAK_DB_PASSWORD`, and `KEYCLOAK_DB_NAME` environment variables passed to the postgres service.

> **Note**: This only runs when the PostgreSQL data volume is empty (first startup). If you already have a running database, create the Keycloak database manually.

### OnlyOffice Configuration

Used with `docker-compose.onlyoffice.yml`:

| Variable | Default | Description |
|----------|---------|-------------|
| `ONLYOFFICE_ENABLED` | `false` | Enable OnlyOffice integration |
| `ONLYOFFICE_PORT` | `8080` | OnlyOffice exposed port |
| `ONLYOFFICE_URL` | `http://onlyoffice` | OnlyOffice internal URL |
| `ONLYOFFICE_API_BASE_URL` | `http://openfilz-api:8081` | API URL for OnlyOffice callbacks |
| `ONLYOFFICE_JWT_SECRET` | `openfilz-onlyoffice-jwt-secret-2024` | JWT secret for OnlyOffice |

### Full-Text Search Configuration (OpenSearch)

Used with `docker-compose.fulltext.yml`:

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENFILZ_FULLTEXT_ACTIVE` | `false` | Enable full-text search |
| `OPENSEARCH_PORT` | `9200` | OpenSearch API exposed port |
| `OPENSEARCH_PERF_PORT` | `9600` | OpenSearch performance port |
| `OPENSEARCH_DASHBOARDS_PORT` | `5601` | OpenSearch Dashboards exposed port |

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
