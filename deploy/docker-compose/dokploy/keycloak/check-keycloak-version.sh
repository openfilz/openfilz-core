#!/bin/sh
set -e

# Verify that every pinned Keycloak image reference in the repo agrees with the
# version in this directory's Dockerfile.
#
# The Dockerfile FROM line is the single source of truth. The remaining
# references are deployment configuration (compose defaults, .env values, Helm
# values) and documentation, which legitimately carry a concrete tag rather than
# deriving one — so instead of removing them, this guard makes them verifiable.
#
# Run from: openfilz-core/deploy/docker-compose/dokploy/keycloak/
# Exits non-zero and lists the offenders if anything has drifted.

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR" && git rev-parse --show-toplevel 2>/dev/null || true)
if [ -z "$REPO_ROOT" ]; then
  REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../../../.." && pwd)
fi

KC_VERSION=$(sed -n 's|^FROM  *quay\.io/keycloak/keycloak:\(.*\)$|\1|p' "$SCRIPT_DIR/Dockerfile")
if [ -z "$KC_VERSION" ]; then
  echo "ERROR: could not read the Keycloak version from $SCRIPT_DIR/Dockerfile" >&2
  exit 1
fi

echo "Expected Keycloak version (from Dockerfile): $KC_VERSION"

# Every "<something>keycloak:<version>" / "keycloak-ee:<version>" pin in the repo.
# Note "keycloak:8080" (the internal Docker DNS URL) does not match, as the
# pattern requires a dotted version.
matches=$(grep -rEn --binary-files=without-match \
  --exclude-dir=target --exclude-dir=node_modules --exclude-dir=.git --exclude-dir=.idea \
  'keycloak(-ee)?:[0-9]+\.[0-9]+(\.[0-9]+)?' "$REPO_ROOT" 2>/dev/null || true)

drift=$(echo "$matches" | grep -vE "keycloak(-ee)?:${KC_VERSION}([^0-9.]|$)" | grep -E 'keycloak(-ee)?:[0-9]' || true)

if [ -n "$drift" ]; then
  echo "ERROR: Keycloak version drift — the following pins do not match $KC_VERSION:" >&2
  echo "$drift" >&2
  exit 1
fi

count=$(echo "$matches" | grep -cE 'keycloak(-ee)?:[0-9]' || true)
echo "OK: all $count pinned Keycloak references are on $KC_VERSION"

# The realm export links a first social sign-in to an existing account with idp-auto-link. That
# step only checks that the identity provider verified the e-mail from Keycloak 26.8.2: on an
# older Keycloak it links without any check, whatever "Trust Email" says, and anyone who controls
# the e-mail a provider reports can sign in as an existing user. So this export must not be
# shipped on an older Keycloak. A tag without a patch number (26.8) is the latest patch.
if grep -q '"idp-auto-link"' "$SCRIPT_DIR/import/realm-export.json"; then
  kc_full=$(printf '%s' "$KC_VERSION" | tr -d '\r')
  case "$kc_full" in *.*.*) ;; *) kc_full="$kc_full.999" ;; esac
  if [ "$(printf '%s\n' 26.8.2 "$kc_full" | sort -V | head -n 1)" != "26.8.2" ]; then
    echo "ERROR: import/realm-export.json uses idp-auto-link, which needs Keycloak 26.8.2 or later to" >&2
    echo "       check the e-mail before linking; the Dockerfile is on $KC_VERSION. Upgrade Keycloak first." >&2
    exit 1
  fi
  echo "OK: idp-auto-link in the realm export, Keycloak $KC_VERSION checks the e-mail before linking"
fi
