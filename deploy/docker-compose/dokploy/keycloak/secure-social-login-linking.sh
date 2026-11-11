#!/bin/bash
# Applies to an EXISTING realm the social sign-in linking rules of realm-export.json.
# Keycloak's --import-realm never updates a realm that exists, so a realm created from an older
# export keeps the old rules until this script runs.
#
# Old rules: Google, GitHub and Microsoft had "Trust Email" on and the first broker login flow
# linked a first social sign-in to the account with the same e-mail without any check. Microsoft
# reports an e-mail that an administrator of any Entra tenant can set to any address, so anyone
# could sign in as an existing user.
#
# New rules (what this script sets):
#   - "Trust Email" off on the identity providers of type github and microsoft that use the
#     "openfilz first broker login" flow. Google keeps it: Google only reports the e-mail of the
#     account that signs in, and with Trust Email off every new Google sign-up would be asked to
#     verify an e-mail Google already verified. Other identity providers are left as they are;
#   - "openfilz first broker login" = idp-create-user-if-unique, then the sub-flow
#     "openfilz link existing account" = idp-auto-link (passes only when the provider itself
#     verified the e-mail or has Trust Email on: Google) or idp-email-verification (a link is e-mailed to the
#     existing account; the provider is linked once it is opened). Needs SMTP on the realm.
#
# Keycloak version: idp-auto-link only checks that the provider verified the e-mail from Keycloak
# 26.8.2. On an older server it links without any check, whatever "Trust Email" says, so there
# the script leaves it out (and removes it if it is there): every first social sign-in to an
# existing account, Google included, then goes through the e-mailed link. Run the script again
# after upgrading to 26.8.2 or later to let a provider that verifies the e-mail link at once.
#
# Identities already linked are not touched. The script only adds what is missing and changes
# only the settings above, so a second run changes nothing.
#
# Run it with the Keycloak image's own kcadm, for example:
#   docker exec -i -e KEYCLOAK_ADMIN_PASSWORD=... <keycloak-container> bash < secure-social-login-linking.sh
#   (then again with -e DRY_RUN=false)
#
# Variables:
#   DRY_RUN                   true (default) prints the plan, false applies it
#   KEYCLOAK_URL              http://localhost:8080
#   KEYCLOAK_REALM            openfilz
#   KEYCLOAK_ADMIN            admin  (KC_BOOTSTRAP_ADMIN_USERNAME if set)
#   KEYCLOAK_ADMIN_PASSWORD   (KC_BOOTSTRAP_ADMIN_PASSWORD if set). To keep it out of the host's
#                             process list and shell history, export it and give docker exec
#                             "-e KEYCLOAK_ADMIN_PASSWORD" with no value.
#   KEYCLOAK_ADMIN_REALM      master
#   SOCIAL_PROVIDER_TYPES     "github microsoft"   (the types whose Trust Email is turned off)
#
# Exit codes: 0 done or nothing to do, 1 error, 2 configuration (flow or realm missing).
set -euo pipefail

KCADM=${KCADM:-/opt/keycloak/bin/kcadm.sh}
DRY_RUN=${DRY_RUN:-true}
KEYCLOAK_URL=${KEYCLOAK_URL:-http://localhost:8080}
REALM=${KEYCLOAK_REALM:-openfilz}
ADMIN=${KEYCLOAK_ADMIN:-${KC_BOOTSTRAP_ADMIN_USERNAME:-admin}}
ADMIN_PASSWORD=${KEYCLOAK_ADMIN_PASSWORD:-${KC_BOOTSTRAP_ADMIN_PASSWORD:-}}
ADMIN_REALM=${KEYCLOAK_ADMIN_REALM:-master}
SOCIAL_PROVIDER_TYPES=${SOCIAL_PROVIDER_TYPES:-"github microsoft"}

FLOW="openfilz first broker login"
SUB="openfilz link existing account"
SUB_DESCRIPTION="Links the IdP identity to the account with the same e-mail: automatically when the IdP verified the e-mail or has Trust Email on (Google only), otherwise through a link e-mailed to the existing account"

changes=0
say()  { echo "[secure-social-login] $*"; }
enc()  { printf '%s' "$1" | sed 's/ /%20/g'; }
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

if [ -z "$ADMIN_PASSWORD" ]; then
  say "KEYCLOAK_ADMIN_PASSWORD is not set"; exit 2
fi
# kcadm reads the password from KC_CLI_PASSWORD: given as --password it would show in the process
# list for as long as the login runs.
KC_CLI_PASSWORD="$ADMIN_PASSWORD" kcadm config credentials --server "$KEYCLOAK_URL" \
  --realm "$ADMIN_REALM" --user "$ADMIN" >/dev/null
if ! kcadm get "realms/$REALM" --fields realm >/dev/null 2>&1; then
  say "realm $REALM not found"; exit 2
fi

# idp-auto-link refuses an e-mail the provider did not verify from Keycloak 26.8.2 only.
AUTO_LINK_MIN=26.8.2
version=$(kcadm get serverinfo --fields 'systemInfo(version)' 2>/dev/null \
  | sed -n 's/.*"version" *: *"\([0-9][0-9.]*\).*/\1/p' | head -n 1)
if [ -n "$version" ] && [ "$(printf '%s\n' "$AUTO_LINK_MIN" "$version" | sort -V | head -n 1)" = "$AUTO_LINK_MIN" ]; then
  auto_link=true
  sub_steps_wanted="idp-auto-link idp-email-verification"
else
  auto_link=false
  sub_steps_wanted="idp-email-verification"
fi
say "realm $REALM, Keycloak ${version:-of unknown version}, DRY_RUN=$DRY_RUN"
if [ "$auto_link" = "false" ]; then
  say "NOTE: before Keycloak $AUTO_LINK_MIN idp-auto-link links without checking the e-mail, so it is"
  say "      left out: every first social sign-in to an existing account gets the e-mailed link."
  say "      Run this script again after upgrading Keycloak."
fi

flows=$(kc_csv authentication/flows --fields alias)
if ! grep -qxF "$FLOW" <<<"$flows"; then
  say "flow '$FLOW' not found: this realm was not created from the OpenFilz export"; exit 2
fi

# csv columns: id, level, providerId, displayName, requirement, priority
executions() { kc_csv "authentication/flows/$(enc "$FLOW")/executions" --fields id,level,providerId,displayName,requirement,priority; }

# 1. The sub-flow and its two steps (sub-flows are not listed with the top-level flows, so look
#    for it among the top flow's executions).
top=$(executions)
if ! grep -q "^[^,]*,0,,$SUB," <<<"$top"; then
  apply "create sub-flow '$SUB' in '$FLOW'" \
    create "authentication/flows/$(enc "$FLOW")/executions/flow" -r "$REALM" \
    -s "alias=$SUB" -s type=basic-flow -s provider=registration-page-form -s "description=$SUB_DESCRIPTION"
  sub_steps=""
else
  sub_steps=$(kc_csv "authentication/flows/$(enc "$SUB")/executions" --fields providerId)
fi
for step in $sub_steps_wanted; do
  if ! grep -qxF "$step" <<<"$sub_steps"; then
    apply "add $step to '$SUB'" \
      create "authentication/flows/$(enc "$SUB")/executions/execution" -r "$REALM" -s "provider=$step"
  fi
done

# 2. idp-auto-link no longer directly in the top flow; every linking step ALTERNATIVE and in this
#    order (Keycloak gives a new execution priority 0, which would put the sub-flow first):
#      idp-create-user-if-unique (10), sub-flow (20); inside it idp-auto-link (10), idp-email-verification (20)
want_priority() {
  case "$1,$2" in
    "0,idp-create-user-if-unique") echo 10 ;;
    "0,$SUB")                      echo 20 ;;
    "1,idp-auto-link")             echo 10 ;;
    "1,idp-email-verification")    echo 20 ;;
  esac
}
if [ "$DRY_RUN" = "false" ]; then
  top=$(executions)
elif ! grep -q "^[^,]*,0,,$SUB," <<<"$top"; then
  changes=$((changes + 1))
  say "plan:  remove idp-auto-link from the top of '$FLOW', set the linking steps to ALTERNATIVE and order them"
  top=""
fi
while IFS=, read -r id level provider name requirement priority; do
  [ -n "$id" ] || continue
  if [ "$level" = "0" ] && [ "$provider" = "idp-auto-link" ]; then
    apply "remove idp-auto-link from the top of '$FLOW'" \
      delete "authentication/executions/$id" -r "$REALM"
    continue
  fi
  if [ "$level" = "1" ] && [ "$provider" = "idp-auto-link" ] && [ "$auto_link" = "false" ]; then
    apply "remove idp-auto-link from '$SUB' (it checks nothing before Keycloak $AUTO_LINK_MIN)" \
      delete "authentication/executions/$id" -r "$REALM"
    continue
  fi
  want=$(want_priority "$level" "${provider:-$name}")
  [ -n "$want" ] || continue
  if [ "$requirement" != "ALTERNATIVE" ] || [ "$priority" != "$want" ]; then
    apply "set '${name}' to ALTERNATIVE, priority $want (was $requirement, $priority)" \
      update "authentication/flows/$(enc "$FLOW")/executions" -r "$REALM" \
      -b "{\"id\":\"$id\",\"requirement\":\"ALTERNATIVE\",\"priority\":$want}"
  fi
done <<<"$top"

# 3. Trust Email off on the social providers that use the flow (not Google, see the header).
#    csv columns: alias, providerId, trustEmail, firstBrokerLoginFlowAlias
while IFS=, read -r alias type trust flow; do
  [ -n "$alias" ] || continue
  if ! grep -qw -- "$type" <<<"$SOCIAL_PROVIDER_TYPES"; then
    if [ "$flow" = "$FLOW" ] && [ "$trust" = "true" ] && [ "$type" != "google" ]; then
      say "NOTE: identity provider $alias ($type) uses '$FLOW' with Trust Email on and is left as it"
      say "      is: a first sign-in through it links to the account with the same e-mail without any"
      say "      check. Keep that only if the provider cannot report an e-mail its user does not own."
    fi
    continue
  fi
  [ "$flow" = "$FLOW" ] || { say "skip:  $alias uses flow '$flow', not '$FLOW'"; continue; }
  if [ "$trust" = "true" ]; then
    apply "Trust Email off on identity provider $alias ($type)" \
      update "identity-provider/instances/$alias" -r "$REALM" -s trustEmail=false
  fi
done < <(kc_csv identity-provider/instances --fields alias,providerId,trustEmail,firstBrokerLoginFlowAlias)

if ! kcadm get "realms/$REALM" --fields 'smtpServer(host)' 2>/dev/null | grep -q '"host" : "[^"]'; then
  say "WARN: the realm has no SMTP server: the link e-mail cannot be sent, so a first social sign-in"
  say "      to an existing account that needs it will fail until SMTP is configured"
fi

if [ "$changes" -eq 0 ]; then
  say "nothing to do"
elif [ "$DRY_RUN" = "false" ]; then
  say "$changes change(s) applied"
else
  say "$changes change(s) planned; run again with DRY_RUN=false to apply"
fi
