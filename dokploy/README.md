# OpenFilz - Dokploy Deployment

Deploy OpenFilz with Keycloak authentication and OnlyOffice document visualization on [Dokploy](https://dokploy.com/).

## Services Included

| Service | Description | Default Domain |
|---------|-------------|----------------|
| **openfilz-web** | Angular frontend | `app.yourdomain.com` |
| **openfilz-api** | Spring Boot API | `api.yourdomain.com` |
| **keycloak** | Authentication server | `keycloak.yourdomain.com` |
| **onlyoffice** | Document server | `onlyoffice.yourdomain.com` |
| **postgres** | PostgreSQL database | Internal only |

## Prerequisites

1. A Dokploy server with Traefik configured
2. Four domains/subdomains pointing to your Dokploy server (A records)
3. SSL certificates will be automatically generated via Let's Encrypt

## Deployment Steps

### 1. Create a New Compose Service in Dokploy

1. Go to Dokploy Dashboard → Projects → Create New
2. Select **Compose** as the service type
3. Choose **Docker Compose** as the Compose Type

### 2. Configure the Compose File

1. Set the **Compose Path** to `./compose.yaml`
2. Copy the contents of `compose.yaml` into Dokploy's editor

### 3. Set Environment Variables

In Dokploy's **Environment** tab, add these variables:

```env
# Required - Replace with your actual domains
WEB_HOSTNAME=app.yourdomain.com
API_HOSTNAME=api.yourdomain.com
KEYCLOAK_HOSTNAME=keycloak.yourdomain.com
ONLYOFFICE_HOSTNAME=onlyoffice.yourdomain.com

# Database - Use strong passwords!
DB_NAME=dms_db
DB_USER=dms_user
DB_PASSWORD=your-strong-db-password

# Keycloak Admin
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=your-strong-admin-password
KEYCLOAK_CLIENT_ID=openfilz-web

# OnlyOffice JWT Secret - Change this!
ONLYOFFICE_JWT_SECRET=your-onlyoffice-jwt-secret

# CORS Origins
CORS_ALLOWED_ORIGINS=https://app.yourdomain.com,https://onlyoffice.yourdomain.com
```

### 4. Upload Keycloak Realm File

Before deploying, upload the Keycloak realm configuration:

1. In Dokploy, go to your compose service → **Advanced** → **File Mounts**
2. Create a new file mount:
   - **Path**: `keycloak/realm-export.json`
   - **Content**: Copy the contents from `dokploy/realm-export.json` (pre-configured for openfilz.org)
3. **Important**: Change the default admin password in the realm file before deploying

Alternatively, copy `realm-export.json` to the `../files/keycloak/` directory on your Dokploy server.

**Default admin user created:**
- Username: `admin`
- Password: `change-this-password` (change before deploying!)
- Roles: All roles (CONTRIBUTOR, CLEANER, AUDITOR, READER)

### 5. Deploy

Click **Deploy** and wait for all services to start (this may take a few minutes).

## Post-Deployment Configuration

### Configure Keycloak

1. Access Keycloak at `https://keycloak.yourdomain.com`
2. Login with admin credentials
3. Create the `openfilz` realm:
   - Go to **Create Realm**
   - Import the realm configuration from `openfilz-api/src/test/resources/keycloak/realm-export.json`
   - Or manually create the realm and client

4. Configure the `openfilz-web` client:
   - **Client ID**: `openfilz-web`
   - **Root URL**: `https://app.yourdomain.com`
   - **Valid redirect URIs**: `https://app.yourdomain.com/*`
   - **Web origins**: `+` (or `https://app.yourdomain.com`)
   - **Client authentication**: OFF (public client)

5. Create users and assign roles (READER, CONTRIBUTOR, CLEANER, AUDITOR)

### Verify Services

| Service | Health Check URL |
|---------|------------------|
| API | `https://api.yourdomain.com/actuator/health` |
| Keycloak | `https://keycloak.yourdomain.com/health/ready` |
| OnlyOffice | `https://onlyoffice.yourdomain.com/healthcheck` |
| Web UI | `https://app.yourdomain.com` |

## Architecture

```
                                   ┌─────────────┐
                                   │   Traefik   │
                                   │  (Dokploy)  │
                                   └──────┬──────┘
                                          │
        ┌─────────────────────────────────┼─────────────────────────────────┐
        │                                 │                                 │
        ▼                                 ▼                                 ▼
┌───────────────┐               ┌─────────────────┐               ┌─────────────────┐
│ openfilz-web  │◄──────────────│  openfilz-api   │───────────────│   onlyoffice    │
│  (Frontend)   │   REST/GraphQL│    (Backend)    │   Document    │ (Doc Server)    │
└───────────────┘               └────────┬────────┘   Editing     └─────────────────┘
                                         │
                        ┌────────────────┴────────────────┐
                        │                                 │
                        ▼                                 ▼
               ┌─────────────────┐               ┌─────────────────┐
               │    postgres     │               │    keycloak     │
               │   (Database)    │               │     (Auth)      │
               └─────────────────┘               └─────────────────┘
```

## Volume Backups

Dokploy can automatically backup named volumes. Configure backups for:

- `postgres-data` - Database (critical)
- `openfilz-storage` - Document storage (critical)
- `keycloak-data` - Keycloak configuration

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
- [OpenFilz Documentation](https://github.com/openfilz/openfilz)
