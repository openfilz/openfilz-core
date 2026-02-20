# OpenFilz - Dokploy Deployment

Deploy OpenFilz with Keycloak authentication and OnlyOffice document visualization on [Dokploy](https://dokploy.com/).

## Services Included

| Service | Description | Default Domain |
|---------|-------------|----------------|
| **openfilz-web** | Angular frontend | `app.yourdomain.com` |
| **openfilz-api** | Spring Boot API | `api.yourdomain.com` |
| **keycloak** | Authentication server | `auth.yourdomain.com` |
| **onlyoffice** | Document server | `docs.yourdomain.com` |
| **postgres** | PostgreSQL database | Internal only |
| **gotenberg** | PDF/Office thumbnail generation | Internal only |
| **opensearch** | Full-text search cluster | Internal only |

## Prerequisites

1. A Dokploy server with Traefik configured
2. Four domains/subdomains pointing to your Dokploy server (A records)
3. SSL certificates will be automatically generated via Let's Encrypt

## Deployment Steps

### 1. Create a New Compose Service in Dokploy

1. Go to Dokploy Dashboard > Projects > Create New
2. Select **Compose** as the service type
3. Choose **Docker Compose** as the Compose Type

### 2. Configure the Compose File

1. Set the **Compose Path** to `./compose.yaml`
2. Copy the contents of `compose.yaml` into Dokploy's editor

### 3. Set Environment Variables

In Dokploy's **Environment** tab, add the variables from `.env.example`. At minimum:

```env
# Required - Replace with your actual domains
WEB_HOSTNAME=app.yourdomain.com
API_HOSTNAME=api.yourdomain.com
KEYCLOAK_HOSTNAME=auth.yourdomain.com
ONLYOFFICE_HOSTNAME=docs.yourdomain.com

# Database - Use strong passwords!
DB_NAME=dms_db
DB_USER=dms_user
DB_PASSWORD=your-strong-db-password

# Keycloak database (auto-created on first startup)
KEYCLOAK_DB_USER=keycloak
KEYCLOAK_DB_PASSWORD=your-strong-keycloak-db-password
KEYCLOAK_DB_NAME=keycloak

# Keycloak Admin
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=your-strong-admin-password

# Keycloak Public URL (used as frontendUrl in realm configuration)
KEYCLOAK_PUBLIC_URL=https://auth.yourdomain.com
# Default roles for new users (up to 4, set unused slots to an already-used role)
KEYCLOAK_DEFAULT_ROLE_1=READER
KEYCLOAK_DEFAULT_ROLE_2=READER
KEYCLOAK_DEFAULT_ROLE_3=READER
KEYCLOAK_DEFAULT_ROLE_4=READER
# Default groups for new users (up to 4, set unused slots to an already-used group)
KEYCLOAK_DEFAULT_GROUP_1=/OPENFILZ/READER
KEYCLOAK_DEFAULT_GROUP_2=/OPENFILZ/READER
KEYCLOAK_DEFAULT_GROUP_3=/OPENFILZ/READER
KEYCLOAK_DEFAULT_GROUP_4=/OPENFILZ/READER

# SMTP (for Keycloak emails)
SMTP_HOST=smtp.example.com
SMTP_PORT=587
SMTP_FROM=noreply@yourdomain.com
SMTP_SSL=false
SMTP_STARTTLS=true
SMTP_AUTH=true
SMTP_USER=your-smtp-user
SMTP_PASSWORD=your-smtp-password

# Identity Providers (Social Login)
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
GITHUB_CLIENT_ID=your-github-client-id
GITHUB_CLIENT_SECRET=your-github-client-secret
MICROSOFT_CLIENT_ID=your-microsoft-client-id
MICROSOFT_CLIENT_SECRET=your-microsoft-client-secret

# OnlyOffice JWT Secret - Change this!
ONLYOFFICE_JWT_SECRET=your-onlyoffice-jwt-secret

# CORS Origins
CORS_ALLOWED_ORIGINS=https://app.yourdomain.com,https://docs.yourdomain.com
```

### 4. Upload ngx-env.js Configuration

Upload the frontend configuration file via Dokploy File Mounts:

1. Go to your compose service > **Advanced** > **File Mounts**
2. Create a file mount at `openfilz-web/ngx-env.js` with your frontend configuration (see `openfilz-web/ngx-env.js` for the template)

### 5. Deploy

Click **Deploy** and wait for all services to start (this may take a few minutes).

## Custom Keycloak Image

This deployment uses a custom Keycloak image (`ghcr.io/openfilz/keycloak:<version>`) that includes:

- **Pre-imported realm**: The OpenFilz realm configuration is baked into the image, eliminating the need for manual realm file uploads
- **Custom themes**: OpenFilz-branded login and email themes
- **Pre-built optimizations**: `kc.sh build` has already been executed for faster startup

The image is built and pushed automatically via GitHub Actions when files in `keycloak/` are changed on the `main` branch. See the `Dockerfile` in `keycloak/` for details.

To use a specific version, set:
```env
KEYCLOAK_IMAGE=ghcr.io/openfilz/keycloak:26.5
```

## Keycloak Database Initialization

The Keycloak database and user are automatically created by PostgreSQL on first startup using the `init-keycloak-db.sh` script. This script:

1. Is mounted into PostgreSQL's `/docker-entrypoint-initdb.d/` directory
2. Reads `KEYCLOAK_DB_USER`, `KEYCLOAK_DB_PASSWORD`, and `KEYCLOAK_DB_NAME` from environment variables
3. Creates the database and user automatically

This replaces the previous `create_db.sql` approach and avoids hardcoded credentials.

## Post-Deployment Configuration

### Configure Keycloak Users

1. Access Keycloak at `https://auth.yourdomain.com`
2. Login with admin credentials
3. The `openfilz` realm is pre-configured from the custom image
4. Create users and assign roles (READER, CONTRIBUTOR, CLEANER, AUDITOR)

### Verify Services

| Service | Health Check URL |
|---------|------------------|
| API | `https://api.yourdomain.com/actuator/health` |
| Keycloak | `https://auth.yourdomain.com/health/ready` |
| OnlyOffice | `https://docs.yourdomain.com/healthcheck` |
| Web UI | `https://app.yourdomain.com` |

## Architecture

```
                         ┌─────────────────┐
                         │     Traefik     │
                         │    (Dokploy)    │
                         └────────┬────────┘
                                  │
                    HTTPS (Let's Encrypt)
                                  │
                 ┌────────────────┼────────────────┐
                 ▼                ▼                ▼
          app.domain.com   api.domain.com   auth.domain.com
          docs.domain.com
                                  │
                 ┌────────────────┴────────────────┐
                 │                                 │
                 │   OpenFilz Services             │
                 │   (see architecture diagram)    │
                 │                                 │
                 └─────────────────────────────────┘
```

For the detailed services architecture, see the [main deploy README](../../README.md#architecture).

## Volume Backups

Dokploy can automatically backup named volumes. Configure backups for:

- `postgres-data` - Database (critical)
- `openfilz-storage` - Document storage (critical)

See [Dokploy Volume Backups](https://docs.dokploy.com/docs/core/docker-compose) for configuration.

## Troubleshooting

### Services Not Starting

Check logs in Dokploy:
- Click on the compose service
- Go to **Logs** tab
- Select the specific service container

### CORS Errors

Ensure `CORS_ALLOWED_ORIGINS` includes all your domains with `https://` prefix.

### Keycloak Connection Issues

The API uses internal Docker networking (`http://keycloak:8080`) to communicate with Keycloak. Ensure:
- Keycloak is healthy before API starts
- The `openfilz` realm exists in Keycloak

### OnlyOffice Not Loading Documents

1. Verify `ONLYOFFICE_JWT_SECRET` matches in both API and OnlyOffice configs
2. Check that OnlyOffice can reach the API at `http://openfilz-api:8081`

## Security Notes

1. **Change all default passwords** before deploying to production
2. **Use strong JWT secrets** for OnlyOffice
3. **Enable SSL** (automatic with Traefik/Let's Encrypt)
4. **Configure Keycloak** password policies and session timeouts
5. **Backup regularly** using Dokploy's backup features

## References

- [Dokploy Documentation](https://docs.dokploy.com/)
- [Docker Compose in Dokploy](https://docs.dokploy.com/docs/core/docker-compose)
- [Dokploy Examples](https://docs.dokploy.com/docs/core/docker-compose/example)
