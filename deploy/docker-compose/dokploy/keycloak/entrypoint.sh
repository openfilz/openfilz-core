#!/bin/bash
set -e

# Substitute environment variable placeholders in realm-export.json before Keycloak imports it.
#
# Uses sed to replace ${VAR} patterns with actual env var values.
# Only substitutes our custom variables — Keycloak's internal placeholders
# (e.g. ${rolesScopeConsentText}) are left untouched.

IMPORT_DIR=/opt/keycloak/data/import

# Where Keycloak tells the API that a session ended (OIDC back-channel logout), so the API stops
# accepting that session's access tokens at once, e.g. after a password change signs out the
# other devices. Empty = not configured: Keycloak then sends nothing and those tokens stay valid
# until they expire.
OPENFILZ_BACKCHANNEL_LOGOUT_URL="${OPENFILZ_BACKCHANNEL_LOGOUT_URL:-}"

if [ -d "$IMPORT_DIR" ]; then
  echo "[entrypoint] Substituting env vars in realm import files"

  # List of variables to substitute (must match placeholders in realm-export.json)
  VARS=(
    KEYCLOAK_PUBLIC_URL
    OPENFILZ_WEB_ROOT_URL
    OPENFILZ_BACKCHANNEL_LOGOUT_URL
    KEYCLOAK_DEFAULT_ROLE_1
    KEYCLOAK_DEFAULT_ROLE_2
    KEYCLOAK_DEFAULT_ROLE_3
    KEYCLOAK_DEFAULT_ROLE_4
    KEYCLOAK_DEFAULT_GROUP_1
    KEYCLOAK_DEFAULT_GROUP_2
    KEYCLOAK_DEFAULT_GROUP_3
    KEYCLOAK_DEFAULT_GROUP_4
    SMTP_HOST
    SMTP_PORT
    SMTP_FROM
    SMTP_SSL
    SMTP_STARTTLS
    SMTP_AUTH
    SMTP_USER
    SMTP_PASSWORD
    GOOGLE_CLIENT_ID
    GOOGLE_CLIENT_SECRET
    GITHUB_CLIENT_ID
    GITHUB_CLIENT_SECRET
    MICROSOFT_CLIENT_ID
    MICROSOFT_CLIENT_SECRET
  )

  for f in "$IMPORT_DIR"/*.json; do
    [ -f "$f" ] || continue
    for var in "${VARS[@]}"; do
      value="${!var}"
      if [ -n "$value" ] || [ "$var" = "OPENFILZ_BACKCHANNEL_LOGOUT_URL" ]; then
        # Escape sed special chars in value: & \ /
        escaped=$(printf '%s' "$value" | sed 's/[&/\]/\\&/g')
        sed -i "s|\${${var}}|${escaped}|g" "$f"
      fi
    done
  done

  echo "[entrypoint] Done substituting env vars"
fi

exec /opt/keycloak/bin/kc.sh "$@"
