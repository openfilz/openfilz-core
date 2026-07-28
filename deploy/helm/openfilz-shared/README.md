# openfilz-shared Helm chart

Shared services for the `openfilz-shared` namespace (migration wave 2): **Keycloak**
(+ its own small PostgreSQL), **OpenSearch** (single node), **Gotenberg**, **ClamAV**
and optionally **OnlyOffice**. Everything runs on the tenant pool
(`openfilz.com/pool: tenant`) and is consumed cross-namespace by `demo-ce`, `demo-ee`
and the per-tenant namespaces.

Env names mirror the authoritative composes:

- Keycloak / Gotenberg / OpenSearch — `openfilz-core/deploy/docker-compose/dokploy/compose.yaml`
- OnlyOffice / antivirus — `openfilz-enterprise/docker/dokploy-compose-ee.yml`

When those composes gain/rename variables, update the templates accordingly.

## Install (openfilz-shared)

```bash
# prerequisites: a cluster (e.g. openfilz-enterprise/deploy/k3s/cluster.yaml), cert-manager wildcard TLS secret
# (wildcard-openfilz-com-tls) synced into the namespace.
kubectl create namespace openfilz-shared
kubectl label namespace openfilz-shared pod-security.kubernetes.io/enforce=baseline

helm install shared . -n openfilz-shared \
  --set keycloak.adminPassword="$(openssl rand -base64 24)" \
  --set keycloak.db.password="$(openssl rand -base64 24)" \
  --set hosts.auth=auth-demo.openfilz.com

kubectl -n openfilz-shared get pods -w   # keycloak-db → keycloak (realm import) …
```

Then add the Cloudflare A/CNAME record for `hosts.auth` → the Hetzner LB IP, and open
`https://auth-demo.openfilz.com`.

> PodSecurity: `baseline`, not `restricted` — OnlyOffice (and the official ClamAV
> image's init) don't currently pass `restricted`. Tighten per component later.

### Values that MUST be set

| Value | Purpose |
|---|---|
| `keycloak.adminPassword` | Keycloak bootstrap admin (or `keycloak.existingSecret`, key `admin-password`) |
| `keycloak.db.password` | Keycloak's PostgreSQL (same secret, key `db-password`) |
| `hosts.auth` | Public Keycloak hostname (`KC_HOSTNAME`, ingress host) |
| `onlyoffice.jwtSecret` | Only when `onlyoffice.enabled=true` (or `onlyoffice.existingSecret`, key `jwt-secret`) — must match `ONLYOFFICE_JWT_SECRET` on every consuming API |
| `hosts.docs` | Only when `onlyoffice.enabled=true` — public OnlyOffice hostname |

Optional: `keycloak.smtp.*` (invitation/reset emails), `keycloak.idp.*` (Google/GitHub/
Microsoft social login) — all default empty.

## In-cluster service names (contract for consumer charts)

Components follow the `{{ .Release.Name }}-<component>` convention, so with release
name `shared` the FQDNs are:

| Service | FQDN | Port |
|---|---|---|
| Keycloak | `shared-keycloak.openfilz-shared.svc.cluster.local` | 8080 |
| OpenSearch | `shared-opensearch.openfilz-shared.svc.cluster.local` | 9200 |
| Gotenberg | `shared-gotenberg.openfilz-shared.svc.cluster.local` | 3000 |
| ClamAV (clamd) | `shared-clamav.openfilz-shared.svc.cluster.local` | 3310 |
| OnlyOffice | `shared-onlyoffice.openfilz-shared.svc.cluster.local` | 80 |

> The `openfilz-ce` chart's default `auth.realmInternalUrl` / `shared.*` values use
> unprefixed names (`keycloak.openfilz-shared…`) — point them at the real
> release-prefixed names above when installing tenants/demos.

## Namespace-label contract (tenant access)

The NetworkPolicies default-deny ingress on the namespace, then allow:

- `kube-system` (Traefik) → keycloak 8080 + onlyoffice 80 (the two ingress hosts);
- any namespace labelled **`openfilz.com/tenant-access: "true"`** → keycloak 8080,
  opensearch 9200, gotenberg 3000, clamav 3310, onlyoffice 80;
- keycloak → keycloak-db 5432.

The provisioner (and manual demo setup) must label every consuming namespace:

```bash
kubectl label namespace demo-ce openfilz.com/tenant-access=true
```

Without the label, that namespace's API pods time out on Keycloak JWT validation,
full-text indexing, thumbnails and antivirus.

## Realm per tenant/demo

The custom Keycloak image bakes the realm export + OpenFilz themes; `--import-realm`
imports it once as the **template** realm (URLs substituted from `KEYCLOAK_PUBLIC_URL`
/ `OPENFILZ_WEB_ROOT_URL` at import). Each demo/tenant then gets its **own realm**
(e.g. `demo-ce`, `cust-<prefix>`), created via the Keycloak Admin API from that
template, with client redirect URIs pointing at the tenant's web host. Consumer charts
set `auth.publicAuthority=https://<hosts.auth>/realms/<realm>` and
`auth.realmInternalUrl=http://shared-keycloak.openfilz-shared.svc.cluster.local:8080/realms/<realm>`.

## ClamAV

The dokploy EE deployment has **no standalone clamd container** — clamd is embedded
inside the native `antivirus-api` image (localhost:3310). Here the shared clamd is the
official `clamav/clamav` image on 3310; per-tenant `antivirus-api` pods point at it via
`OPENFILZ_ANTIVIRUSAPI_CLAMAV_HOST`/`_PORT` (config `openfilz.antivirus-api.clamav.*`).
The signature DB lives on a PVC so restarts don't re-download; the startup probe allows
10 minutes for the initial freshclam download. If you need the EE tuning from
`docker/demo/clamav/clamd.conf` (StreamMaxLength/MaxFileSize/MaxScanSize 1024M+), mount
a custom `clamd.conf` — not wired into values yet (see below).

## OnlyOffice notes

- `JWT_ENABLED/JWT_SECRET/JWT_HEADER=Authorization` + `ALLOW_META_IP_ADDRESS` /
  `ALLOW_PRIVATE_IP_ADDRESS` mirror the EE compose.
- The dokploy deployment adds a Traefik middleware forcing
  `X-Forwarded-Proto: https`. On k3s, Traefik terminates TLS for the Ingress and sets
  `X-Forwarded-Proto: https` itself, so no middleware is templated. If OnlyOffice
  still generates `http://` asset URLs behind your setup, add a Traefik `Middleware`
  CR with `headers.customRequestHeaders.X-Forwarded-Proto: https` and reference it via
  `ingress.annotations`:
  `traefik.ingress.kubernetes.io/router.middlewares: openfilz-shared-<name>@kubernetescrd`.
- `Data`/logs PVCs are off by default (fonts/certs cache is rebuildable); enable via
  `onlyoffice.persistence.*.enabled` for production tenants.

## Not yet done (follow-ups)

- Custom `clamd.conf` ConfigMap (EE stream/file-size limits) via values.

Validated (2026-07-09, helm v4.2.2): `helm lint` clean; `helm template` renders 17
manifests (default) / 20 (+onlyoffice), all YAML-parse verified.
- OnlyOffice Traefik middleware CR (only if X-Forwarded-Proto proves necessary).
- OpenSearch snapshots/backup; index-per-tenant hygiene jobs.
- Keycloak production hardening: HA (external Infinispan), PodDisruptionBudget,
  metrics scraping (management port 9000 is exposed on the Service already).
