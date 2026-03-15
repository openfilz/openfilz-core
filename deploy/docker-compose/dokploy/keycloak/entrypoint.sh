#!/bin/bash
set -e

# Substitute environment variable placeholders in realm-export.json before Keycloak imports it.
#
# IMPORTANT: We use envsubst with an explicit variable list so that Keycloak's
# internal placeholders (e.g. ${rolesScopeConsentText}) are left untouched.

IMPORT_DIR=/opt/keycloak/data/import

if [ -d "$IMPORT_DIR" ]; then
  VARS='${KEYCLOAK_PUBLIC_URL}'
  VARS+=' ${OPENFILZ_WEB_ROOT_URL}'
  VARS+=' ${KEYCLOAK_DEFAULT_ROLE_1} ${KEYCLOAK_DEFAULT_ROLE_2} ${KEYCLOAK_DEFAULT_ROLE_3} ${KEYCLOAK_DEFAULT_ROLE_4}'
  VARS+=' ${KEYCLOAK_DEFAULT_GROUP_1} ${KEYCLOAK_DEFAULT_GROUP_2} ${KEYCLOAK_DEFAULT_GROUP_3} ${KEYCLOAK_DEFAULT_GROUP_4}'
  VARS+=' ${SMTP_HOST} ${SMTP_PORT} ${SMTP_FROM} ${SMTP_SSL} ${SMTP_STARTTLS} ${SMTP_AUTH} ${SMTP_USER} ${SMTP_PASSWORD}'
  VARS+=' ${GOOGLE_CLIENT_ID} ${GOOGLE_CLIENT_SECRET}'
  VARS+=' ${GITHUB_CLIENT_ID} ${GITHUB_CLIENT_SECRET}'
  VARS+=' ${MICROSOFT_CLIENT_ID} ${MICROSOFT_CLIENT_SECRET}'

  for f in "$IMPORT_DIR"/*.json; do
    [ -f "$f" ] || continue
    echo "Substituting env vars in $(basename "$f")"
    envsubst "$VARS" < "$f" > "${f}.tmp" && mv "${f}.tmp" "$f"
  done
fi

exec /opt/keycloak/bin/kc.sh "$@"
