#!/bin/sh
set -e

# Build and push the OpenFilz Community Edition Keycloak image
# Run from: openfilz-core/deploy/docker-compose/dokploy/keycloak/

docker build -t ghcr.io/openfilz/keycloak:26.5 .

docker push ghcr.io/openfilz/keycloak:26.5
