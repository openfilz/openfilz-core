#!/bin/sh
set -e

# Build and push the OpenFilz Community Edition Keycloak image
# Run from: openfilz-core/deploy/docker-compose/dokploy/keycloak/
#
# The Keycloak version is NOT hardcoded here: it is read from the Dockerfile's
# FROM line, which is the single source of truth for the whole repo. The
# build-keycloak.yml workflow derives the tag the same way, so a local build and
# a CI build can never disagree.

KC_VERSION=$(sed -n 's|^FROM  *quay\.io/keycloak/keycloak:\(.*\)$|\1|p' Dockerfile)
if [ -z "$KC_VERSION" ]; then
  echo "ERROR: could not read the Keycloak version from the Dockerfile FROM line" >&2
  exit 1
fi

IMAGE="ghcr.io/openfilz/keycloak:$KC_VERSION"

# Fail early if any pinned reference elsewhere in the repo disagrees.
sh ./check-keycloak-version.sh

echo "Building $IMAGE"
docker build -t "$IMAGE" .

echo "Pushing $IMAGE"
docker push "$IMAGE"
