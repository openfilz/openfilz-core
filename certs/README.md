# OpenFilz mTLS Configuration

This directory contains scripts and certificates for enabling mutual TLS (mTLS) authentication between ImgProxy and openfilz-api.

## Overview

When mTLS is enabled:
1. **openfilz-api** runs on HTTPS and requires clients to present a valid certificate
2. **ImgProxy** presents a client certificate when fetching source images from openfilz-api
3. The `/api/v1/thumbnails/source/*` endpoint is protected by certificate validation
4. Only certificates matching the DN pattern `CN=imgproxy.*` are accepted

## Quick Start

### 1. Generate Certificates

```bash
# Linux/macOS
cd certs
chmod +x generate-mtls-certs.sh
./generate-mtls-certs.sh

# Windows
cd certs
generate-mtls-certs.bat
```

### 2. Start Services with mTLS

```bash
# Full deployment (all services in Docker)
docker-compose -f docker-compose.yml \
               -f docker-compose-thumbnails.yml \
               -f docker-compose-mtls.yml \
               --profile thumbnails --profile mtls up -d

# Development (API in IDE, only ImgProxy in Docker)
docker-compose -f docker-compose-imgproxy-dev.yml \
               -f docker-compose-mtls.yml \
               --profile mtls-dev up -d
```

### 3. Configure openfilz-api (IDE Development)

When running openfilz-api from your IDE, add these environment variables. The server runs on **both** HTTP and HTTPS:
- **HTTP (8081)**: For browser/frontend access (no certificate issues)
- **HTTPS (8443)**: For ImgProxy mTLS access

```bash
# Enable thumbnails and mTLS
export OPENFILZ_THUMBNAIL_ACTIVE=true
export OPENFILZ_THUMBNAIL_MTLS_ACCESS_ENABLED=true

# mTLS server configuration (starts HTTPS on port 8443)
export OPENFILZ_MTLS_PORT=8443
export OPENFILZ_MTLS_KEYSTORE_PATH=./certs/server/server.p12
export OPENFILZ_MTLS_KEYSTORE_PASSWORD=serverpass
export OPENFILZ_MTLS_TRUSTSTORE_PATH=./certs/truststore/truststore.p12
export OPENFILZ_MTLS_TRUSTSTORE_PASSWORD=changeit
export OPENFILZ_MTLS_CLIENT_AUTH=want

# URLs
export OPENFILZ_INTERNAL_API_BASE_URL=https://host.docker.internal:8443
export IMGPROXY_URL=http://localhost:8082
export GOTENBERG_URL=http://localhost:8083
```

With this configuration:
- Your **frontend** continues using `http://localhost:8081` (no certificate issues!)
- **ImgProxy** uses `https://host.docker.internal:8443` with its client certificate

## Certificate Structure

After running the generation script, the following files are created:

```
certs/
├── ca/
│   ├── ca.key              # CA private key (password protected)
│   └── ca.crt              # CA certificate (root of trust)
├── server/
│   ├── server.key          # Server private key
│   ├── server.crt          # Server certificate (for openfilz-api)
│   ├── server.p12          # Server PKCS12 keystore (for Spring Boot)
│   └── server.cnf          # Server certificate config (generated)
├── client/
│   ├── imgproxy.key        # Client private key (for ImgProxy)
│   ├── imgproxy.crt        # Client certificate (for ImgProxy)
│   └── imgproxy.cnf        # Client certificate config (generated)
├── truststore/
│   └── truststore.p12      # Truststore containing CA cert
├── generate-mtls-certs.sh  # Generation script (Linux/macOS)
├── generate-mtls-certs.bat # Generation script (Windows)
└── README.md               # This file
```

## Default Passwords

| File | Password |
|------|----------|
| ca/ca.key | `capassword` |
| server/server.p12 | `serverpass` |
| truststore/truststore.p12 | `changeit` |

**Important:** Change these passwords before using in production!

## How It Works

### Certificate Chain

```
OpenFilz-CA (Root Certificate Authority)
├── openfilz-api (Server Certificate)
│   └── CN=openfilz-api
│   └── SANs: localhost, host.docker.internal, openfilz-api, 127.0.0.1
└── imgproxy-client (Client Certificate)
    └── CN=imgproxy-client
```

### Authentication Flow

```
┌─────────────┐                              ┌──────────────┐
│  ImgProxy   │                              │ openfilz-api │
└─────────────┘                              └──────────────┘
       │                                            │
       │  1. TLS Handshake Request                  │
       │────────────────────────────────────────────►
       │                                            │
       │  2. Server Certificate (server.crt)        │
       │◄────────────────────────────────────────────
       │                                            │
       │  3. Client Certificate Request             │
       │◄────────────────────────────────────────────
       │                                            │
       │  4. Client Certificate (imgproxy.crt)      │
       │────────────────────────────────────────────►
       │                                            │
       │  5. Verify CN matches "imgproxy.*"         │
       │                                            │
       │  6. Authenticated! Serve thumbnail source  │
       │◄────────────────────────────────────────────
       │                                            │
```

## ImgProxy Configuration

ImgProxy uses these environment variables for mTLS:

| Variable | Description | Example |
|----------|-------------|---------|
| `IMGPROXY_NETWORK_SSL_CERT_PATH` | Path to client certificate | `/certs/client.crt` |
| `IMGPROXY_NETWORK_SSL_KEY_PATH` | Path to client private key | `/certs/client.key` |
| `IMGPROXY_NETWORK_SSL_ROOT_CERT_PATH` | Path to CA certificate | `/certs/ca.crt` |

These are automatically configured in `docker-compose-mtls.yml`.

## openfilz-api Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SERVER_SSL_ENABLED` | Enable HTTPS | `false` |
| `SERVER_SSL_KEY_STORE` | Path to server keystore | - |
| `SERVER_SSL_KEY_STORE_PASSWORD` | Keystore password | - |
| `SERVER_SSL_CLIENT_AUTH` | Client auth mode | `none` |
| `SERVER_SSL_TRUST_STORE` | Path to truststore | - |
| `SERVER_SSL_TRUST_STORE_PASSWORD` | Truststore password | - |
| `OPENFILZ_THUMBNAIL_MTLS_ACCESS_ENABLED` | Enable mTLS for thumbnails | `false` |
| `OPENFILZ_THUMBNAIL_MTLS_ACCESS_ALLOWED_DN_PATTERN` | Allowed DN regex | `CN=imgproxy.*` |

### Client Auth Modes

- `none`: No client certificate required (default)
- `want`: Request client certificate but don't require it
- `need`: Require client certificate for all connections

Use `want` to allow regular clients while still accepting mTLS from ImgProxy.

## Troubleshooting

### Check Certificate Details

```bash
# View CA certificate
openssl x509 -in certs/ca/ca.crt -text -noout

# View server certificate
openssl x509 -in certs/server/server.crt -text -noout

# View client certificate
openssl x509 -in certs/client/imgproxy.crt -text -noout

# Verify certificate chain
openssl verify -CAfile certs/ca/ca.crt certs/client/imgproxy.crt
```

### Test mTLS Connection

```bash
# Test with client certificate
curl -v --cert certs/client/imgproxy.crt \
        --key certs/client/imgproxy.key \
        --cacert certs/ca/ca.crt \
        https://localhost:8443/api/v1/thumbnails/source/test

# Test without client certificate (should fail for mTLS endpoint)
curl -v --cacert certs/ca/ca.crt \
        https://localhost:8443/api/v1/thumbnails/source/test
```

### Common Issues

1. **"No client certificate presented"**
   - Verify ImgProxy has the certificate mounted correctly
   - Check `IMGPROXY_NETWORK_SSL_CERT_PATH` and `IMGPROXY_NETWORK_SSL_KEY_PATH`

2. **"Client certificate not authorized"**
   - Certificate CN doesn't match `allowed-dn-pattern`
   - Default pattern is `CN=imgproxy.*`, certificate CN is `imgproxy-client`

3. **"Certificate verify failed"**
   - CA certificate not in truststore
   - Regenerate certificates or add CA to truststore

4. **Connection refused on port 8443**
   - openfilz-api not started with SSL enabled
   - Check `SERVER_SSL_ENABLED=true`

## Production Recommendations

1. **Change default passwords** in the generation script
2. **Use proper CA** from your organization or a commercial CA
3. **Set certificate validity** appropriate for your environment
4. **Rotate certificates** before expiration
5. **Secure private keys** with proper file permissions
6. **Use `client-auth: need`** if all clients should authenticate
7. **Monitor certificate expiration** with alerting

## Regenerating Certificates

To regenerate certificates (e.g., before expiration):

```bash
# Remove existing certificates
rm -rf certs/ca certs/server certs/client certs/truststore

# Regenerate
./generate-mtls-certs.sh
```

Then restart the services to pick up new certificates.
