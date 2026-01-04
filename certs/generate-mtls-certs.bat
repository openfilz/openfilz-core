@echo off
REM =============================================================================
REM OpenFilz mTLS Certificate Generation Script (Windows)
REM =============================================================================
REM
REM This script generates all certificates required for mTLS between ImgProxy
REM and openfilz-api.
REM
REM Requirements:
REM   - OpenSSL for Windows (https://slproweb.com/products/Win32OpenSSL.html)
REM   - keytool (from JDK)
REM
REM Usage:
REM   generate-mtls-certs.bat
REM
REM =============================================================================

setlocal enabledelayedexpansion

echo =========================================
echo OpenFilz mTLS Certificate Generator
echo =========================================

REM Configuration
set CA_DAYS=3650
set CERT_DAYS=365
set KEY_SIZE=4096
set CA_SUBJECT=/C=FR/ST=IDF/L=Paris/O=OpenFilz/OU=Security/CN=OpenFilz-CA
set SERVER_SUBJECT=/C=FR/ST=IDF/L=Paris/O=OpenFilz/OU=API/CN=openfilz-api
set CLIENT_SUBJECT=/C=FR/ST=IDF/L=Paris/O=OpenFilz/OU=ImgProxy/CN=imgproxy-client

REM Passwords (change in production!)
set CA_KEY_PASS=capassword
set SERVER_KEYSTORE_PASS=serverpass
set TRUSTSTORE_PASS=changeit

REM Create directories
echo [1/7] Creating directory structure...
if not exist ca mkdir ca
if not exist server mkdir server
if not exist client mkdir client
if not exist truststore mkdir truststore

REM =============================================================================
REM Generate CA
REM =============================================================================
echo [2/7] Generating CA private key and certificate...

if not exist ca\ca.key (
    openssl genrsa -aes256 -passout pass:%CA_KEY_PASS% -out ca\ca.key %KEY_SIZE%
)

if not exist ca\ca.crt (
    openssl req -x509 -new -nodes -key ca\ca.key -passin pass:%CA_KEY_PASS% -sha256 -days %CA_DAYS% -subj "%CA_SUBJECT%" -out ca\ca.crt
)
echo    CA certificate created: ca\ca.crt

REM =============================================================================
REM Generate Server Certificate
REM =============================================================================
echo [3/7] Generating server certificate for openfilz-api...

REM Create server config
(
echo [req]
echo default_bits = %KEY_SIZE%
echo prompt = no
echo default_md = sha256
echo distinguished_name = dn
echo req_extensions = req_ext
echo.
echo [dn]
echo C = FR
echo ST = IDF
echo L = Paris
echo O = OpenFilz
echo OU = API
echo CN = openfilz-api
echo.
echo [req_ext]
echo subjectAltName = @alt_names
echo.
echo [alt_names]
echo DNS.1 = openfilz-api
echo DNS.2 = localhost
echo DNS.3 = host.docker.internal
echo DNS.4 = 127.0.0.1
echo IP.1 = 127.0.0.1
) > server\server.cnf

if not exist server\server.key (
    openssl genrsa -out server\server.key %KEY_SIZE%
)

openssl req -new -key server\server.key -config server\server.cnf -out server\server.csr

openssl x509 -req -in server\server.csr -CA ca\ca.crt -CAkey ca\ca.key -passin pass:%CA_KEY_PASS% -CAcreateserial -out server\server.crt -days %CERT_DAYS% -sha256 -extensions req_ext -extfile server\server.cnf

openssl pkcs12 -export -in server\server.crt -inkey server\server.key -certfile ca\ca.crt -name openfilz-api -out server\server.p12 -password pass:%SERVER_KEYSTORE_PASS%

del server\server.csr 2>nul
echo    Server certificate created: server\server.crt
echo    Server keystore created: server\server.p12

REM =============================================================================
REM Generate Client Certificate
REM =============================================================================
echo [4/7] Generating client certificate for ImgProxy...

(
echo [req]
echo default_bits = %KEY_SIZE%
echo prompt = no
echo default_md = sha256
echo distinguished_name = dn
echo.
echo [dn]
echo C = FR
echo ST = IDF
echo L = Paris
echo O = OpenFilz
echo OU = ImgProxy
echo CN = imgproxy-client
) > client\imgproxy.cnf

if not exist client\imgproxy.key (
    openssl genrsa -out client\imgproxy.key %KEY_SIZE%
)

openssl req -new -key client\imgproxy.key -config client\imgproxy.cnf -out client\imgproxy.csr

openssl x509 -req -in client\imgproxy.csr -CA ca\ca.crt -CAkey ca\ca.key -passin pass:%CA_KEY_PASS% -CAcreateserial -out client\imgproxy.crt -days %CERT_DAYS% -sha256

del client\imgproxy.csr 2>nul
echo    Client certificate created: client\imgproxy.crt

REM =============================================================================
REM Create Truststore
REM =============================================================================
echo [5/7] Creating truststore for openfilz-api...

if exist truststore\truststore.p12 del truststore\truststore.p12
keytool -importcert -file ca\ca.crt -alias openfilz-ca -keystore truststore\truststore.p12 -storetype PKCS12 -storepass %TRUSTSTORE_PASS% -noprompt

echo    Truststore created: truststore\truststore.p12

REM =============================================================================
REM Verify certificates
REM =============================================================================
echo [6/7] Verifying certificates...

echo    Verifying server certificate chain...
openssl verify -CAfile ca\ca.crt server\server.crt

echo    Verifying client certificate chain...
openssl verify -CAfile ca\ca.crt client\imgproxy.crt

REM =============================================================================
REM Display info
REM =============================================================================
echo [7/7] Certificate information...
echo.
echo CA Certificate:
openssl x509 -in ca\ca.crt -noout -subject -issuer -dates
echo.
echo Server Certificate (openfilz-api):
openssl x509 -in server\server.crt -noout -subject -issuer -dates
echo.
echo Client Certificate (ImgProxy):
openssl x509 -in client\imgproxy.crt -noout -subject -issuer -dates
echo.

REM =============================================================================
REM Summary
REM =============================================================================
echo =========================================
echo Certificate Generation Complete!
echo =========================================
echo.
echo Files created:
echo   ca\ca.crt                  - CA certificate
echo   ca\ca.key                  - CA private key (password: %CA_KEY_PASS%)
echo   server\server.p12          - Server keystore (password: %SERVER_KEYSTORE_PASS%)
echo   server\server.crt          - Server certificate
echo   server\server.key          - Server private key
echo   client\imgproxy.crt        - ImgProxy client certificate
echo   client\imgproxy.key        - ImgProxy client private key
echo   truststore\truststore.p12  - Truststore (password: %TRUSTSTORE_PASS%)
echo.
echo Next steps:
echo   1. Start services with mTLS:
echo      docker-compose -f docker-compose.yml -f docker-compose-mtls.yml --profile mtls up -d
echo.
echo Important: Update passwords before using in production!
echo.

endlocal
