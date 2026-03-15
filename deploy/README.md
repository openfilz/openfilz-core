# OpenFilz Deployment

This directory contains all deployment configurations for OpenFilz.

## Deployment Methods

| Method | Directory | Use Case |
|--------|-----------|----------|
| [Docker Compose](docker-compose/) | `docker-compose/` | Local development and testing |
| [Dokploy](docker-compose/dokploy/) | `docker-compose/dokploy/` | Production deployment with Dokploy |
| [Helm](helm/) | `helm/` | Kubernetes deployment |

## Architecture

```mermaid
graph TB
    subgraph Clients["Clients"]
        WEB["openfilz-web<br/><sub>Angular frontend</sub>"]
    end

    subgraph Auth["Identity Provider"]
        KC["Keycloak<br/><sub>OIDC · JWT</sub>"]
    end

    API["openfilz-api<br/><sub>Spring WebFlux backend</sub>"]

    subgraph Required["Required"]
        PG["PostgreSQL<br/><sub>database</sub>"]
    end

    subgraph Storage["Storage <i>(choose one)</i>"]
        FS["Local FS"]
        S3["MinIO / S3"]
    end

    subgraph Optional["Optional Services"]
        OO["OnlyOffice<br/><sub>editing</sub>"]
        OS["OpenSearch<br/><sub>search</sub>"]
        GOT["Gotenberg<br/><sub>thumbnails</sub>"]
    end

    WEB -->|"REST · GraphQL"| API
    WEB -->|"OIDC login"| KC
    API -->|"JWT validation"| KC
    API --> PG
    API --> FS
    API --> S3
    API --> OO
    API --> OS
    API --> GOT

    classDef reqStyle fill:#ecfdf5,stroke:#10b981,stroke-width:2px
    classDef optStyle fill:#f8fafc,stroke:#94a3b8,stroke-width:1px,stroke-dasharray:5 5

    class PG,FS,S3 reqStyle
    class OO,OS,GOT optStyle
```

## Environment Variables

All configuration is done via environment variables. See the `.env.example` files in each deployment directory for the full list of available variables.

### Core Variables

| Variable | Description |
|----------|-------------|
| `DB_NAME` | PostgreSQL database name |
| `DB_USER` | PostgreSQL username |
| `DB_PASSWORD` | PostgreSQL password |
| `KEYCLOAK_DB_USER` | Keycloak database user (auto-created) |
| `KEYCLOAK_DB_PASSWORD` | Keycloak database password |
| `KEYCLOAK_DB_NAME` | Keycloak database name |
| `KEYCLOAK_IMAGE` | Custom Keycloak Docker image |
| `KEYCLOAK_ADMIN` | Keycloak admin username |
| `KEYCLOAK_ADMIN_PASSWORD` | Keycloak admin password |
| `OPENFILZ_API_IMAGE` | OpenFilz API Docker image |
| `OPENFILZ_WEB_IMAGE` | OpenFilz Web Docker image |
| `CORS_ALLOWED_ORIGINS` | Comma-separated allowed CORS origins |
| `ONLYOFFICE_JWT_SECRET` | JWT secret for OnlyOffice |

## Custom Keycloak Image

OpenFilz uses a custom Keycloak image (`ghcr.io/openfilz/keycloak:<version>`) that includes:
- Pre-imported OpenFilz realm configuration
- Custom OpenFilz login and email themes
- Pre-built Keycloak optimizations (`kc.sh build` already executed)

The image is built automatically via GitHub Actions when files in `docker-compose/dokploy/keycloak/` change on the `main` branch.

## Keycloak Database Initialization

PostgreSQL automatically creates the Keycloak database and user on first startup using the `init-keycloak-db.sh` script mounted into `/docker-entrypoint-initdb.d/`. The script reads `KEYCLOAK_DB_USER`, `KEYCLOAK_DB_PASSWORD`, and `KEYCLOAK_DB_NAME` from environment variables.
