# OpenFilz Docker Guide

This guide explains how to use the various Docker Compose configurations for OpenFilz.

## Quick Reference

| Compose File | Purpose | Use Case |
|--------------|---------|----------|
| `docker-compose.yml` | Full deployment | Production / Full stack testing |
| `docker-compose-thumbnails.yml` | Thumbnail services overlay | Add thumbnails to full deployment |
| `docker-compose-mtls.yml` | mTLS overlay | Add mTLS security to deployment |
| `docker-compose-imgproxy-dev.yml` | Dev thumbnail services | Local dev with API in IDE |
| `docker-compose-imgproxy-mtls-dev.yml` | Dev thumbnails + mTLS | Local dev with mTLS enabled |

---

## 1. Full Deployment (`docker-compose.yml`)

Starts all core services for a complete OpenFilz deployment.

### Base Services (always started)

| Service | Port | Description |
|---------|------|-------------|
| postgres | 5432 | PostgreSQL database |
| openfilz-api | 8081 | Backend REST/GraphQL API |
| openfilz-web | 4200 | Frontend web application |

### Optional Services (via profiles)

| Profile | Service | Port | Description |
|---------|---------|------|-------------|
| `auth` | keycloak | 8180 | OAuth2/OIDC authentication |
| `onlyoffice` | onlyoffice | 8080 | Document editing server |
| `fulltext` | opensearch | 9200 | Full-text search engine |
| `fulltext` | opensearch-dashboards | 5601 | OpenSearch UI |

### Commands

```bash
# Start base services only (no authentication)
docker-compose up -d

# Start with Keycloak authentication
docker-compose --profile auth up -d

# Start with OnlyOffice document editing
docker-compose --profile onlyoffice up -d

# Start with full-text search
docker-compose --profile fulltext up -d

# Start with multiple profiles
docker-compose --profile auth --profile onlyoffice --profile fulltext up -d

# Stop all services
docker-compose down

# Stop and remove volumes (clean reset)
docker-compose down -v

# View logs
docker-compose logs -f

# View logs for specific service
docker-compose logs -f openfilz-api
```

### Environment Variables

Copy `.env.example` to `.env` and customize:

```bash
cp .env.example .env
```

Key variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_PASSWORD` | `dms_password` | PostgreSQL password |
| `OPENFILZ_SECURITY_NO_AUTH` | `true` | Disable authentication |
| `STORAGE_TYPE` | `local` | Storage backend (local/minio) |

---

## 2. Thumbnail Services (`docker-compose-thumbnails.yml`)

Adds thumbnail generation capabilities to the full deployment. **Must be used with `docker-compose.yml`**.

### Services

| Service | Port | Description |
|---------|------|-------------|
| imgproxy | 8082 | Fast image thumbnail generation |
| gotenberg | 8083 | PDF/Office document conversion |
| redis | 6379 | Distributed thumbnail queue (optional) |

### Commands

```bash
# Start with thumbnail support
docker-compose -f docker-compose.yml -f docker-compose-thumbnails.yml \
  --profile thumbnails up -d

# Start with thumbnails and Redis (for multi-instance)
docker-compose -f docker-compose.yml -f docker-compose-thumbnails.yml \
  --profile thumbnails --profile thumbnails-redis up -d

# Stop all services
docker-compose -f docker-compose.yml -f docker-compose-thumbnails.yml \
  --profile thumbnails down

# View ImgProxy logs
docker-compose -f docker-compose.yml -f docker-compose-thumbnails.yml \
  --profile thumbnails logs -f imgproxy
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENFILZ_THUMBNAIL_ACTIVE` | `true` | Enable thumbnail generation |
| `IMGPROXY_PORT` | `8082` | ImgProxy exposed port |
| `GOTENBERG_PORT` | `8083` | Gotenberg exposed port |

---

## 3. mTLS Security Overlay (`docker-compose-mtls.yml`)

Adds mutual TLS authentication between ImgProxy and openfilz-api. **Must be used with other compose files**.

### Prerequisites

Generate certificates first:

```bash
cd certs
./generate-mtls-certs.sh   # Linux/Mac
generate-mtls-certs.bat    # Windows
```

### Commands

```bash
# Full deployment with thumbnails and mTLS
docker-compose -f docker-compose.yml \
  -f docker-compose-thumbnails.yml \
  -f docker-compose-mtls.yml \
  --profile thumbnails --profile mtls up -d

# Stop all services
docker-compose -f docker-compose.yml \
  -f docker-compose-thumbnails.yml \
  -f docker-compose-mtls.yml \
  --profile thumbnails --profile mtls down
```

### Dual-Port Architecture

With mTLS enabled, openfilz-api runs on **both** HTTP and HTTPS simultaneously:

| Port | Protocol | Purpose |
|------|----------|---------|
| 8081 | HTTP | Browser/Frontend access (no cert issues) |
| 8443 | HTTPS+mTLS | ImgProxy access (client certificate required) |

This means your frontend continues using `http://localhost:8081` while ImgProxy uses `https://openfilz-api:8443` with its client certificate.

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENFILZ_THUMBNAIL_MTLS_ACCESS_ENABLED` | `false` | Enable mTLS (starts HTTPS server) |
| `OPENFILZ_MTLS_PORT` | `8443` | HTTPS port for mTLS |
| `OPENFILZ_MTLS_KEYSTORE_PATH` | - | Path to server keystore |
| `OPENFILZ_MTLS_KEYSTORE_PASSWORD` | - | Keystore password |
| `OPENFILZ_MTLS_TRUSTSTORE_PATH` | - | Path to truststore |
| `OPENFILZ_MTLS_TRUSTSTORE_PASSWORD` | - | Truststore password |

---

## 4. Development: Thumbnails Only (`docker-compose-imgproxy-dev.yml`)

Standalone thumbnail services for local development. Use when running openfilz-api from your IDE.

### Services

| Service | Port | Description |
|---------|------|-------------|
| imgproxy | 8082 | Image thumbnail generation |
| gotenberg | 8083 | PDF/Office conversion |

### Commands

```bash
# Start thumbnail services
docker-compose -f docker-compose-imgproxy-dev.yml up -d

# Stop thumbnail services
docker-compose -f docker-compose-imgproxy-dev.yml down

# View logs
docker-compose -f docker-compose-imgproxy-dev.yml logs -f

# Restart ImgProxy only
docker-compose -f docker-compose-imgproxy-dev.yml restart imgproxy
```

### IDE Configuration

Configure openfilz-api in your IDE with:

```properties
OPENFILZ_THUMBNAIL_ACTIVE=true
IMGPROXY_URL=http://localhost:8082
GOTENBERG_URL=http://localhost:8083
OPENFILZ_INTERNAL_API_BASE_URL=http://host.docker.internal:8081
```

---

## 5. Development: Thumbnails with mTLS (`docker-compose-imgproxy-mtls-dev.yml`)

Standalone thumbnail services with mTLS for local development. Use when testing mTLS with openfilz-api in your IDE.

### Prerequisites

Generate certificates first:

```bash
cd certs
./generate-mtls-certs.sh   # Linux/Mac
generate-mtls-certs.bat    # Windows
```

### Services

| Service | Port | Description |
|---------|------|-------------|
| imgproxy | 8082 | Image thumbnails with mTLS client cert |
| gotenberg | 8083 | PDF/Office conversion |

### Commands

```bash
# Start thumbnail services with mTLS
docker-compose -f docker-compose-imgproxy-mtls-dev.yml up -d

# Stop thumbnail services
docker-compose -f docker-compose-imgproxy-mtls-dev.yml down

# View logs (useful for debugging mTLS)
docker-compose -f docker-compose-imgproxy-mtls-dev.yml logs -f imgproxy

# Rebuild after certificate changes
docker-compose -f docker-compose-imgproxy-mtls-dev.yml up -d --force-recreate
```

### IDE Configuration

Configure openfilz-api in your IDE:

```properties
# Enable thumbnails and mTLS
OPENFILZ_THUMBNAIL_ACTIVE=true
OPENFILZ_THUMBNAIL_MTLS_ACCESS_ENABLED=true

# mTLS server configuration (starts HTTPS on port 8443)
OPENFILZ_MTLS_PORT=8443
OPENFILZ_MTLS_KEYSTORE_PATH=./certs/server/server.p12
OPENFILZ_MTLS_KEYSTORE_PASSWORD=serverpass
OPENFILZ_MTLS_TRUSTSTORE_PATH=./certs/truststore/truststore.p12
OPENFILZ_MTLS_TRUSTSTORE_PASSWORD=changeit
OPENFILZ_MTLS_CLIENT_AUTH=want

# URLs
OPENFILZ_INTERNAL_API_BASE_URL=https://host.docker.internal:8443
IMGPROXY_URL=http://localhost:8082
GOTENBERG_URL=http://localhost:8083
```

With this configuration:
- **Frontend** uses `http://localhost:8081` (no certificate issues)
- **ImgProxy** uses `https://host.docker.internal:8443` with mTLS

---

## Common Operations

### Check Service Health

```bash
# Check all running containers
docker ps

# Check specific service health
docker inspect --format='{{.State.Health.Status}}' openfilz-api

# Check ImgProxy health
curl http://localhost:8082/health

# Check Gotenberg health
curl http://localhost:8083/health

# Check API health
curl http://localhost:8081/actuator/health
```

### View Logs

```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f openfilz-api

# Last 100 lines
docker-compose logs --tail=100 openfilz-api

# Since timestamp
docker-compose logs --since="2024-01-01T00:00:00" openfilz-api
```

### Restart Services

```bash
# Restart all
docker-compose restart

# Restart specific service
docker-compose restart openfilz-api

# Recreate containers (after config changes)
docker-compose up -d --force-recreate

# Recreate specific service
docker-compose up -d --force-recreate openfilz-api
```

### Clean Up

```bash
# Stop and remove containers
docker-compose down

# Also remove volumes (database data, etc.)
docker-compose down -v

# Also remove images
docker-compose down --rmi all

# Remove unused Docker resources
docker system prune -f
```

### Update Images

```bash
# Pull latest images
docker-compose pull

# Pull and restart
docker-compose pull && docker-compose up -d
```

---

## Development Scenarios

### Scenario 1: Backend Development (No Thumbnails)

Run only the database, develop API in IDE:

```bash
# Start only PostgreSQL
docker-compose up -d postgres

# Run openfilz-api from IDE with:
DB_HOST=localhost
DB_PORT=5432
```

### Scenario 2: Backend Development (With Thumbnails)

```bash
# Start PostgreSQL + thumbnail services
docker-compose up -d postgres
docker-compose -f docker-compose-imgproxy-dev.yml up -d

# Run openfilz-api from IDE with:
DB_HOST=localhost
OPENFILZ_THUMBNAIL_ACTIVE=true
IMGPROXY_URL=http://localhost:8082
GOTENBERG_URL=http://localhost:8083
```

### Scenario 3: Backend Development (With mTLS)

```bash
# Generate certificates
cd certs && ./generate-mtls-certs.sh && cd ..

# Start PostgreSQL + thumbnail services with mTLS
docker-compose up -d postgres
docker-compose -f docker-compose-imgproxy-mtls-dev.yml up -d

# Run openfilz-api from IDE with SSL config (see section 5)
```

### Scenario 4: Full Stack Testing

```bash
# Start everything
docker-compose -f docker-compose.yml \
  -f docker-compose-thumbnails.yml \
  --profile thumbnails up -d

# Access:
# - Frontend: http://localhost:4200
# - API: http://localhost:8081
# - Swagger: http://localhost:8081/swagger-ui.html
```

### Scenario 5: Production-like with Authentication

```bash
# Start with Keycloak
docker-compose --profile auth up -d

# Configure .env first:
OPENFILZ_SECURITY_NO_AUTH=false
KEYCLOAK_ISSUER_URI=http://keycloak:8080/realms/openfilz
```

---

## Troubleshooting

### Container Won't Start

```bash
# Check logs
docker-compose logs openfilz-api

# Check container status
docker ps -a

# Inspect container
docker inspect openfilz-api
```

### Database Connection Issues

```bash
# Verify PostgreSQL is running
docker-compose ps postgres

# Test connection
docker exec -it openfilz-postgres psql -U dms_user -d dms_db -c "SELECT 1"

# Check database logs
docker-compose logs postgres
```

### mTLS Issues

```bash
# Verify certificates exist
ls -la certs/client/
ls -la certs/server/

# Check ImgProxy can read certificates
docker exec openfilz-imgproxy-mtls-dev ls -la /certs/

# Test mTLS connection manually
curl -v --cert certs/client/imgproxy.crt \
        --key certs/client/imgproxy.key \
        --cacert certs/ca/ca.crt \
        https://localhost:8443/actuator/health

# Enable debug logging in ImgProxy
# Set IMGPROXY_LOG_LEVEL=debug in docker-compose file
```

### Port Conflicts

```bash
# Check what's using a port
# Linux/Mac:
lsof -i :8081
# Windows:
netstat -ano | findstr :8081

# Use different ports via environment variables
API_PORT=9081 docker-compose up -d
```

### Out of Disk Space

```bash
# Check Docker disk usage
docker system df

# Clean up unused resources
docker system prune -a --volumes
```

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Docker Network: openfilz-network             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌─────────────┐     ┌─────────────┐     ┌─────────────┐           │
│  │  postgres   │     │ openfilz-api│     │ openfilz-web│           │
│  │   :5432     │◄────│    :8081    │────►│   :4200     │           │
│  └─────────────┘     └──────┬──────┘     └─────────────┘           │
│                             │                                       │
│         ┌───────────────────┼───────────────────┐                  │
│         ▼                   ▼                   ▼                  │
│  ┌─────────────┐     ┌─────────────┐     ┌─────────────┐           │
│  │  imgproxy   │     │  gotenberg  │     │  keycloak   │           │
│  │   :8082     │     │   :8083     │     │   :8180     │           │
│  └─────────────┘     └─────────────┘     └─────────────┘           │
│     (thumbnails)       (thumbnails)           (auth)               │
│                                                                     │
│  ┌─────────────┐     ┌─────────────┐                               │
│  │ opensearch  │     │ onlyoffice  │                               │
│  │   :9200     │     │   :8080     │                               │
│  └─────────────┘     └─────────────┘                               │
│     (fulltext)         (onlyoffice)                                │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘

Profiles:
  - Base: postgres, openfilz-api, openfilz-web
  - thumbnails: imgproxy, gotenberg
  - auth: keycloak
  - fulltext: opensearch, opensearch-dashboards
  - onlyoffice: onlyoffice
  - mtls: Adds SSL/mTLS configuration
```

---

## File Reference

| File | Description |
|------|-------------|
| `docker-compose.yml` | Main compose file with all services |
| `docker-compose-thumbnails.yml` | Thumbnail overlay (use with main) |
| `docker-compose-mtls.yml` | mTLS overlay (use with main) |
| `docker-compose-imgproxy-dev.yml` | Standalone dev thumbnails |
| `docker-compose-imgproxy-mtls-dev.yml` | Standalone dev thumbnails + mTLS |
| `.env.example` | Example environment variables |
| `certs/` | mTLS certificates directory |
| `certs/generate-mtls-certs.sh` | Certificate generation (Linux/Mac) |
| `certs/generate-mtls-certs.bat` | Certificate generation (Windows) |
| `certs/README.md` | mTLS certificate documentation |
