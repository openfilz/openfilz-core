# OpenFilz Community Edition - Dokploy Deployment

Deploy OpenFilz with Keycloak authentication and OnlyOffice document editing on [Dokploy](https://dokploy.com/).

## Services Included

| Service | Description | Default Domain |
|---------|-------------|----------------|
| **openfilz-web** | Angular frontend | `app.yourdomain.com` |
| **openfilz-api** | Spring Boot API | `api.yourdomain.com` |
| **keycloak** | Authentication server | `auth.yourdomain.com` |
| **onlyoffice** | Document server | `docs.yourdomain.com` |
| **opensearch-dashboards** | Search analytics | `ops.yourdomain.com` |
| **postgres** | PostgreSQL database | Internal only |
| **gotenberg** | PDF/Office thumbnail generation | Internal only |
| **opensearch** | Full-text search cluster (2-node) | Internal only |
| **storage-init** | Volume ownership init container | Runs once |

## Prerequisites

1. A Dokploy server with Traefik configured
2. Five domains/subdomains pointing to your Dokploy server (A records)
3. SSL certificates will be automatically generated via Let's Encrypt

## Deployment Steps

### 1. Create a New Compose Service in Dokploy

1. Go to Dokploy Dashboard > Projects > Create New
2. Select **Compose** as the service type
3. Choose **Docker Compose** as the Compose Type

### 2. Configure the Compose File

1. Set the **Compose Path** to `./compose.yaml`
2. Copy the contents of `compose.yaml` into Dokploy's editor

### 3. Set Up `OPENFILZ_FILES_BASE` (Bind Mounts)

The compose file uses `${OPENFILZ_FILES_BASE}` for all host-side bind mount paths. This variable points to a directory on your Dokploy server where you place configuration files that containers need at runtime.

#### What goes in `OPENFILZ_FILES_BASE`?

The compose file bind-mounts two files from this directory:

| Container path | Host path (`${OPENFILZ_FILES_BASE}/...`) | Purpose |
|---|---|---|
| `/docker-entrypoint-initdb.d/init-keycloak-db.sh` | `db-create.sh` | Creates the Keycloak database on first PostgreSQL startup |
| `/usr/share/nginx/html/ngx-env.js` | `openfilz-web/ngx-env.js` | Runtime config for the Angular frontend (API URL, Keycloak URL, etc.) |

#### Setup instructions

Both files are available in this repository. You just need to transfer them to your Dokploy server and edit `ngx-env.js` with your actual domains.

**1. SSH into your Dokploy server and create the directory:**

```bash
sudo mkdir -p /etc/dokploy/openfilz-ce/openfilz-web
```

**2. From your local machine, transfer the files with `scp`:**

> **Warning (Windows users):** Shell scripts must have Unix line endings (LF). If you edit them on Windows, make sure your editor saves with LF, not CRLF. The `.gitattributes` in this repo enforces LF for `*.sh` files via Git, but files created or edited outside Git may still get CRLF. You can fix them with `dos2unix db-create.sh` or `sed -i 's/\r$//' db-create.sh` on the server.

```bash
# From the openfilz-core/deploy/docker-compose/dokploy/ directory:
scp db-create.sh user@your-server:/etc/dokploy/openfilz-ce/db-create.sh
```

Then create `ngx-env.js` with your actual domains. You can either edit locally and `scp`, or create it directly on the server:

```bash
# Edit ngx-env.js locally first (replace yourdomain.com with your actual domain),
# then transfer it:
scp openfilz-web/ngx-env.js user@your-server:/etc/dokploy/openfilz-ce/openfilz-web/ngx-env.js
```

The `ngx-env.js` file should contain (replace `yourdomain.com` with your actual domain):

```javascript
globalThis._NGX_ENV_ = {
    "NG_APP_API_URL": "https://api.yourdomain.com/api/v1",
    "NG_APP_GRAPHQL_URL": "https://api.yourdomain.com/graphql/v1",
    "NG_APP_AUTHENTICATION_ENABLED": "true",
    "NG_APP_AUTHENTICATION_AUTHORITY": "https://auth.yourdomain.com/realms/openfilz",
    "NG_APP_AUTHENTICATION_CLIENT_ID": "openfilz-web",
    "NG_APP_ONLYOFFICE_ENABLED": "true",
    "NG_APP_ONLYOFFICE_URL": "https://docs.yourdomain.com"
};
```

A ready-to-edit template is available at `openfilz-web/ngx-env.js` in this directory.

**3. On the server, make the init script executable:**

```bash
sudo chmod +x /etc/dokploy/openfilz-ce/db-create.sh
```

**4. Set the environment variable** in Dokploy's Environment tab (or `.env.dokploy-ce`):

```env
OPENFILZ_FILES_BASE=/etc/dokploy/openfilz-ce
```

Your final directory on the server should look like:

```
/etc/dokploy/openfilz-ce/
├── db-create.sh
└── openfilz-web/
    └── ngx-env.js
```

> **Note:** The compose file will fail with a clear error (`Set OPENFILZ_FILES_BASE in .env`) if this variable is not set.

### 4. Set Environment Variables

In Dokploy's **Environment** tab, add the variables from `.env.dokploy-ce` (or `.env.example` as a template). At minimum:

```env
# Bind mounts base path (see step 3)
OPENFILZ_FILES_BASE=/etc/dokploy/openfilz-ce

# Required - Replace with your actual domains
WEB_HOSTNAME=app.yourdomain.com
API_HOSTNAME=api.yourdomain.com
KEYCLOAK_HOSTNAME=auth.yourdomain.com
ONLYOFFICE_HOSTNAME=docs.yourdomain.com
OPENSEARCH_DASHBOARDS_HOSTNAME=ops.yourdomain.com

# Database - Use strong passwords!
POSTGRES_USER=dms_user
POSTGRES_PASSWORD=your-strong-db-password
POSTGRES_DB=dms_db

# Keycloak database (auto-created on first startup)
KEYCLOAK_DB_USER=keycloak
KEYCLOAK_DB_PASSWORD=your-strong-keycloak-db-password
KEYCLOAK_DB_NAME=keycloak

# Keycloak Admin
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=your-strong-admin-password

# Default roles for new users (up to 4, set unused slots to an already-used role)
# Available roles: READER, CONTRIBUTOR, AUDITOR, CLEANER
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

# OpenSearch
OPENSEARCH_INITIAL_ADMIN_PASSWORD=yourStrongPassword123!
# Generate with: htpasswd -nb admin yourpassword | sed -e 's/\$/\$\$/g'
OPENSEARCH_DASHBOARDS_BASIC_AUTH=admin:$$apr1$$...hashed...
```

### 5. Deploy

Click **Deploy** and wait for all services to start (this may take a few minutes).

## Custom Keycloak Image

This deployment uses a custom Keycloak image (`ghcr.io/openfilz/keycloak:26.5`) that includes:

- **Pre-imported realm**: The OpenFilz realm configuration is baked into the image
- **Custom themes**: OpenFilz-branded login and email themes
- **Pre-built optimizations**: `kc.sh build` has already been executed for faster startup

To build and push the image manually:

```bash
cd keycloak/
./build-push-keycloak-image.sh
```

To use a specific version, set:
```env
KEYCLOAK_IMAGE=ghcr.io/openfilz/keycloak:26.5
```

## Keycloak Database Initialization

The Keycloak database and user are automatically created by PostgreSQL on first startup using the `db-create.sh` script (sourced from `init-keycloak-db.sh`). This script:

1. Is bind-mounted from `${OPENFILZ_FILES_BASE}/db-create.sh` into PostgreSQL's `/docker-entrypoint-initdb.d/` directory
2. Reads `KEYCLOAK_DB_USER`, `KEYCLOAK_DB_PASSWORD`, and `KEYCLOAK_DB_NAME` from environment variables
3. Creates the database and user automatically

> **Important:** This script only runs on first PostgreSQL initialization (when `postgres-data` volume is empty). If you need to change the Keycloak DB password later, you must `ALTER USER` inside PostgreSQL directly.

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
            ┌──────────┬──────────┼──────────┬──────────┐
            ▼          ▼          ▼          ▼          ▼
       app.domain  api.domain  auth.domain  docs.domain  ops.domain
            │          │          │          │          │
        ┌───┘    ┌─────┘    ┌────┘    ┌─────┘    ┌────┘
        ▼        ▼          ▼         ▼          ▼
   openfilz   openfilz   keycloak  onlyoffice  opensearch
     -web      -api                             -dashboards
                 │          │
        ┌────────┼──────────┘
        ▼        ▼
     postgres  opensearch (2-node cluster)
        │
        ▼
   gotenberg (thumbnails)
```

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

### `OPENFILZ_FILES_BASE` Error

If you see `Set OPENFILZ_FILES_BASE in .env`, the variable is not set. Add it to the Dokploy Environment tab pointing to the directory containing your configuration files.

### Keycloak DB Authentication Failed

If Keycloak fails with "password authentication failed for user keycloak":
- The `db-create.sh` init script only runs when the `postgres-data` volume is first created
- If you changed `KEYCLOAK_DB_PASSWORD` after initial setup, reset it inside PostgreSQL:
  ```sql
  ALTER USER keycloak WITH PASSWORD 'new-password';
  ```

### CORS Errors

Ensure `CORS_ALLOWED_ORIGINS` includes all your domains with `https://` prefix.

### Keycloak Connection Issues

The API uses internal Docker networking (`http://keycloak:8080`) to communicate with Keycloak. Ensure:
- Keycloak is healthy before API starts (enforced by `depends_on` with `condition: service_healthy`)
- The `openfilz` realm exists in Keycloak

### OnlyOffice Not Loading Documents

1. Verify `ONLYOFFICE_JWT_SECRET` matches in both API and OnlyOffice configs
2. Check that OnlyOffice can reach the API at `http://openfilz-api:8080`

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
