#!/bin/bash
# Makes the Keycloak account console (/realms/<realm>/account) work in an EXISTING realm, as
# realm-export.json now does for a new one. Keycloak's --import-realm never updates a realm that
# exists, so a realm created from an older export keeps the problem until this script runs.
#
# The problem: the export defines its own client scopes, so the clients Keycloak created at import
# (account, account-console) got no client scope at all, and the export's "roles" scope maps realm
# roles only. The account console's token then has no resource_access.account.roles and the
# account REST API answers 403: the page shows "Something went wrong".
#
# What this script sets on account-console (and nothing else):
#   - default client scopes basic, roles, profile, email, and optional offline_access (those that
#     exist in the realm);
#   - a "client roles" mapper (resource_access.${client_id}.roles) on account-console itself. It is
#     not added to the shared "roles" scope, so the tokens of the other clients do not change;
#     fullScopeAllowed stays off, so the claim only holds the account roles mapped to the console;
#   - the scope mapping account -> manage-account, view-groups, if it is missing;
#   - the account roles view-profile and manage-account in the realm's default roles, if missing.
#
# The script only adds what is missing, so a second run changes nothing.
#
# Run it with the Keycloak image's own kcadm, for example:
#   docker exec -i <keycloak-container> bash < fix-account-console.sh
#   (then again with -e DRY_RUN=false)
#
# Variables:
#   DRY_RUN                   true (default) prints the plan, false applies it
#   KEYCLOAK_URL              http://localhost:8080
#   KEYCLOAK_REALM            openfilz
#   KEYCLOAK_ADMIN            admin  (KC_BOOTSTRAP_ADMIN_USERNAME if set)
#   KEYCLOAK_ADMIN_PASSWORD   (KC_BOOTSTRAP_ADMIN_PASSWORD if set)
#   KEYCLOAK_ADMIN_REALM      master
#
# Exit codes: 0 done or nothing to do, 1 error, 2 configuration (realm or client missing).
set -euo pipefail

KCADM=${KCADM:-/opt/keycloak/bin/kcadm.sh}
DRY_RUN=${DRY_RUN:-true}
KEYCLOAK_URL=${KEYCLOAK_URL:-http://localhost:8080}
REALM=${KEYCLOAK_REALM:-openfilz}
ADMIN=${KEYCLOAK_ADMIN:-${KC_BOOTSTRAP_ADMIN_USERNAME:-admin}}
ADMIN_PASSWORD=${KEYCLOAK_ADMIN_PASSWORD:-${KC_BOOTSTRAP_ADMIN_PASSWORD:-}}
ADMIN_REALM=${KEYCLOAK_ADMIN_REALM:-master}

DEFAULT_SCOPES="basic roles profile email"
OPTIONAL_SCOPES="offline_access"
MAPPER_NAME="client roles"

changes=0
say()  { echo "[account-console] $*"; }
apply() {
  changes=$((changes + 1))
  if [ "$DRY_RUN" = "false" ]; then
    say "apply: $1"; shift; kcadm "$@" >/dev/null
  else
    say "plan:  $1"
  fi
}
# The admin session goes to a file of its own, removed on exit: kcadm's default file would stay
# in the Keycloak container with a usable admin token in it.
KCADM_CONFIG_FILE=$(mktemp)
trap 'rm -f "$KCADM_CONFIG_FILE"' EXIT
kcadm()  { "$KCADM" "$@" --config "$KCADM_CONFIG_FILE"; }
kc_csv() { kcadm get "$@" -r "$REALM" --format csv --noquotes; }
client_uuid() { kc_csv clients -q "clientId=$1" --fields id | head -n 1; }

if [ -z "$ADMIN_PASSWORD" ]; then
  say "KEYCLOAK_ADMIN_PASSWORD is not set"; exit 2
fi
kcadm config credentials --server "$KEYCLOAK_URL" --realm "$ADMIN_REALM" \
  --user "$ADMIN" --password "$ADMIN_PASSWORD" >/dev/null
if ! kcadm get "realms/$REALM" --fields realm >/dev/null 2>&1; then
  say "realm $REALM not found"; exit 2
fi
say "realm $REALM, DRY_RUN=$DRY_RUN"

console=$(client_uuid account-console)
account=$(client_uuid account)
if [ -z "$console" ] || [ -z "$account" ]; then
  say "client account or account-console not found in realm $REALM"; exit 2
fi

# 1. Client scopes. csv columns: id, name
realm_scopes=$(kc_csv client-scopes --fields id,name)
# (plain bash: the Keycloak image has no awk)
scope_id() {
  local id name
  while IFS=, read -r id name; do
    if [ "$name" = "$1" ]; then echo "$id"; return; fi
  done <<<"$realm_scopes"
}
have_default=$(kc_csv "clients/$console/default-client-scopes" --fields name)
have_optional=$(kc_csv "clients/$console/optional-client-scopes" --fields name)
for kind in default optional; do
  if [ "$kind" = default ]; then wanted=$DEFAULT_SCOPES; have=$have_default; else wanted=$OPTIONAL_SCOPES; have=$have_optional; fi
  for name in $wanted; do
    if grep -qxF "$name" <<<"$have_default"$'\n'"$have_optional"; then
      continue
    fi
    id=$(scope_id "$name")
    if [ -z "$id" ]; then
      say "skip:  client scope $name does not exist in realm $REALM"
      continue
    fi
    apply "give account-console the $kind client scope $name" \
      update "clients/$console/$kind-client-scopes/$id" -r "$REALM" -b '{}' -n
  done
done

# 2. The client-roles mapper on account-console. csv columns: name, protocolMapper
if ! kc_csv "clients/$console/protocol-mappers/models" --fields name,protocolMapper \
    | grep -q ',oidc-usermodel-client-role-mapper$'; then
  apply "add the '$MAPPER_NAME' mapper (resource_access.\${client_id}.roles) to account-console" \
    create "clients/$console/protocol-mappers/models" -r "$REALM" -f - <<'JSON'
{"name": "client roles", "protocol": "openid-connect", "protocolMapper": "oidc-usermodel-client-role-mapper",
 "consentRequired": false,
 "config": {"multivalued": "true", "claim.name": "resource_access.${client_id}.roles", "jsonType.label": "String",
            "access.token.claim": "true", "id.token.claim": "false", "userinfo.token.claim": "false",
            "introspection.token.claim": "true"}}
JSON
fi

# 3. Scope mapping account -> account-console (a stock realm has it; check anyway).
mapped=$(kc_csv "clients/$console/scope-mappings/clients/$account" --fields name)
for role in manage-account view-groups; do
  if ! grep -qxF "$role" <<<"$mapped"; then
    apply "map the account role $role to account-console" \
      create "clients/$console/scope-mappings/clients/$account" -r "$REALM" -f - <<JSON
[$(kcadm get "clients/$account/roles/$role" -r "$REALM")]
JSON
  fi
done

# 4. The account roles new users get (Keycloak puts them in the default roles of a realm it creates
#    the account client for; check anyway).
default_role=$(kcadm get "realms/$REALM" --fields 'defaultRole(name)' | sed -n 's/.*"name" *: *"\([^"]*\)".*/\1/p' | head -n 1)
if [ -n "$default_role" ]; then
  in_default=$(kc_csv "roles/$default_role/composites/clients/$account" --fields name)
  for role in view-profile manage-account; do
    if ! grep -qxF "$role" <<<"$in_default"; then
      apply "add the account role $role to the default roles ($default_role)" \
        create "roles/$default_role/composites" -r "$REALM" -f - <<JSON
[$(kcadm get "clients/$account/roles/$role" -r "$REALM")]
JSON
    fi
  done
fi

if [ "$changes" -eq 0 ]; then
  say "nothing to do"
elif [ "$DRY_RUN" = "false" ]; then
  say "$changes change(s) applied"
else
  say "$changes change(s) planned; run again with DRY_RUN=false to apply"
fi
