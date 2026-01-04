#!/bin/bash
# =============================================================================
# OpenFilz mTLS Certificate Generation Script
# =============================================================================
#
# This script generates all certificates required for mTLS between ImgProxy
# and openfilz-api.
#
# Generated files:
#   ca/
#     ca.key          - CA private key
#     ca.crt          - CA certificate
#   server/
#     server.key      - Server private key (openfilz-api)
#     server.crt      - Server certificate (openfilz-api)
#     server.p12      - Server PKCS12 keystore (for Spring Boot)
#   client/
#     imgproxy.key    - Client private key (ImgProxy)
#     imgproxy.crt    - Client certificate (ImgProxy)
#   truststore/
#     truststore.p12  - Truststore containing CA cert (for openfilz-api)
#
# Usage:
#   ./generate-mtls-certs.sh
#
# Requirements:
#   - openssl
#   - keytool (from JDK, for PKCS12 conversion)
#
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Configuration
CA_DAYS=3650                    # CA validity: 10 years
CERT_DAYS=365                   # Cert validity: 1 year
KEY_SIZE=4096                   # RSA key size
CA_SUBJECT="/C=FR/ST=IDF/L=Paris/O=OpenFilz/OU=Security/CN=OpenFilz-CA"
SERVER_SUBJECT="/C=FR/ST=IDF/L=Paris/O=OpenFilz/OU=API/CN=openfilz-api"
CLIENT_SUBJECT="/C=FR/ST=IDF/L=Paris/O=OpenFilz/OU=ImgProxy/CN=imgproxy-client"

# Passwords (change these in production!)
CA_KEY_PASS="capassword"
SERVER_KEYSTORE_PASS="serverpass"
TRUSTSTORE_PASS="changeit"

echo "========================================="
echo "OpenFilz mTLS Certificate Generator"
echo "========================================="

# Create directory structure
echo "[1/7] Creating directory structure..."
mkdir -p ca server client truststore

# =============================================================================
# Step 1: Generate CA (Certificate Authority)
# =============================================================================
echo "[2/7] Generating CA private key and certificate..."

if [ ! -f ca/ca.key ]; then
    openssl genrsa -aes256 -passout pass:"$CA_KEY_PASS" -out ca/ca.key "$KEY_SIZE"
fi

if [ ! -f ca/ca.crt ]; then
    openssl req -x509 -new -nodes \
        -key ca/ca.key \
        -passin pass:"$CA_KEY_PASS" \
        -sha256 \
        -days "$CA_DAYS" \
        -subj "$CA_SUBJECT" \
        -out ca/ca.crt
fi

echo "   CA certificate created: ca/ca.crt"

# =============================================================================
# Step 2: Generate Server Certificate (for openfilz-api)
# =============================================================================
echo "[3/7] Generating server certificate for openfilz-api..."

# Create server config with SANs
cat > server/server.cnf << EOF
[req]
default_bits = $KEY_SIZE
prompt = no
default_md = sha256
distinguished_name = dn
req_extensions = req_ext

[dn]
C = FR
ST = IDF
L = Paris
O = OpenFilz
OU = API
CN = openfilz-api

[req_ext]
subjectAltName = @alt_names

[alt_names]
DNS.1 = openfilz-api
DNS.2 = localhost
DNS.3 = host.docker.internal
DNS.4 = 127.0.0.1
IP.1 = 127.0.0.1
EOF

# Generate server private key
if [ ! -f server/server.key ]; then
    openssl genrsa -out server/server.key "$KEY_SIZE"
fi

# Generate server CSR
openssl req -new \
    -key server/server.key \
    -config server/server.cnf \
    -out server/server.csr

# Sign server certificate with CA
openssl x509 -req \
    -in server/server.csr \
    -CA ca/ca.crt \
    -CAkey ca/ca.key \
    -passin pass:"$CA_KEY_PASS" \
    -CAcreateserial \
    -out server/server.crt \
    -days "$CERT_DAYS" \
    -sha256 \
    -extensions req_ext \
    -extfile server/server.cnf

# Create PKCS12 keystore for Spring Boot
openssl pkcs12 -export \
    -in server/server.crt \
    -inkey server/server.key \
    -certfile ca/ca.crt \
    -name openfilz-api \
    -out server/server.p12 \
    -password pass:"$SERVER_KEYSTORE_PASS"

rm -f server/server.csr
echo "   Server certificate created: server/server.crt"
echo "   Server keystore created: server/server.p12"

# =============================================================================
# Step 3: Generate Client Certificate (for ImgProxy)
# =============================================================================
echo "[4/7] Generating client certificate for ImgProxy..."

# Create client config
cat > client/imgproxy.cnf << EOF
[req]
default_bits = $KEY_SIZE
prompt = no
default_md = sha256
distinguished_name = dn

[dn]
C = FR
ST = IDF
L = Paris
O = OpenFilz
OU = ImgProxy
CN = imgproxy-client
EOF

# Generate client private key (unencrypted for ImgProxy)
if [ ! -f client/imgproxy.key ]; then
    openssl genrsa -out client/imgproxy.key "$KEY_SIZE"
fi

# Generate client CSR
openssl req -new \
    -key client/imgproxy.key \
    -config client/imgproxy.cnf \
    -out client/imgproxy.csr

# Sign client certificate with CA
openssl x509 -req \
    -in client/imgproxy.csr \
    -CA ca/ca.crt \
    -CAkey ca/ca.key \
    -passin pass:"$CA_KEY_PASS" \
    -CAcreateserial \
    -out client/imgproxy.crt \
    -days "$CERT_DAYS" \
    -sha256

rm -f client/imgproxy.csr
echo "   Client certificate created: client/imgproxy.crt"

# =============================================================================
# Step 4: Create Truststore for openfilz-api
# =============================================================================
echo "[5/7] Creating truststore for openfilz-api..."

# Create PKCS12 truststore containing CA certificate
# This is used by openfilz-api to validate client certificates
keytool -importcert \
    -file ca/ca.crt \
    -alias openfilz-ca \
    -keystore truststore/truststore.p12 \
    -storetype PKCS12 \
    -storepass "$TRUSTSTORE_PASS" \
    -noprompt 2>/dev/null || true

echo "   Truststore created: truststore/truststore.p12"

# =============================================================================
# Step 5: Verify certificates
# =============================================================================
echo "[6/7] Verifying certificates..."

echo "   Verifying server certificate chain..."
openssl verify -CAfile ca/ca.crt server/server.crt

echo "   Verifying client certificate chain..."
openssl verify -CAfile ca/ca.crt client/imgproxy.crt

# =============================================================================
# Step 6: Display certificate info
# =============================================================================
echo "[7/7] Certificate information..."
echo ""
echo "CA Certificate:"
openssl x509 -in ca/ca.crt -noout -subject -issuer -dates | sed 's/^/   /'
echo ""
echo "Server Certificate (openfilz-api):"
openssl x509 -in server/server.crt -noout -subject -issuer -dates | sed 's/^/   /'
echo ""
echo "Client Certificate (ImgProxy):"
openssl x509 -in client/imgproxy.crt -noout -subject -issuer -dates | sed 's/^/   /'
echo ""

# =============================================================================
# Summary
# =============================================================================
echo "========================================="
echo "Certificate Generation Complete!"
echo "========================================="
echo ""
echo "Files created:"
echo "  ca/ca.crt                  - CA certificate"
echo "  ca/ca.key                  - CA private key (password: $CA_KEY_PASS)"
echo "  server/server.p12          - Server keystore (password: $SERVER_KEYSTORE_PASS)"
echo "  server/server.crt          - Server certificate"
echo "  server/server.key          - Server private key"
echo "  client/imgproxy.crt        - ImgProxy client certificate"
echo "  client/imgproxy.key        - ImgProxy client private key"
echo "  truststore/truststore.p12  - Truststore (password: $TRUSTSTORE_PASS)"
echo ""
echo "Next steps:"
echo "  1. Start services with mTLS:"
echo "     docker-compose -f docker-compose.yml -f docker-compose-mtls.yml --profile mtls up -d"
echo ""
echo "  2. Or for development with IDE:"
echo "     docker-compose -f docker-compose-imgproxy-dev.yml -f docker-compose-mtls.yml --profile mtls-dev up -d"
echo ""
echo "Important: Update passwords before using in production!"
echo ""
