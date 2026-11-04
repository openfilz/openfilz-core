# openfilz-shared Helm chart

Shared services for the `openfilz-shared` namespace: **Keycloak** (+ its own
small PostgreSQL), **OpenSearch** (single node), **Gotenberg** and optionally
**OnlyOffice**. They are deployed **once** and consumed cross-namespace by
every OpenFilz application namespace (demo or per-tenant).

Env names mirror the authoritative compose
(`deploy/docker-compose/dokploy/compose.yaml`); update the templates when it
gains/renames variables.

## Which components does an OpenFilz deployment need?

| Component | Needed for | Skip if |
|---|---|---|
| Keycloak (+ keycloak-db) | Authentication (all real deployments) | you already run Keycloak elsewhere, or `auth.enabled=false` eval installs |
| Gotenberg | Thumbnails / document conversion (`features.thumbnails`) | thumbnails stay off |
| OpenSearch | Full-text search (`features.fulltext`) | full-text stays off (`opensearch.enabled=false`) |
| OnlyOffice | Collaborative editing (`onlyoffice.enabled`, off by default) | no collaborative editing |

## Install

```bash
# prerequisites: a Kubernetes cluster with an ingress controller, and a
# wildcard TLS secret (wildcard-openfilz-com-tls) synced into the namespace
# (e.g. via cert-manager).
kubectl create namespace openfilz-shared
kubectl label namespace openfilz-shared pod-security.kubernetes.io/enforce=baseline

helm install shared . -n openfilz-shared \
  --set keycloak.adminPassword="$(openssl rand -base64 24)" \
  --set keycloak.db.password="$(openssl rand -base64 24)" \
  --set hosts.auth=auth-demo.openfilz.com

kubectl -n openfilz-shared get pods -w   # keycloak-db → keycloak (realm import) …
```

Then add the DNS A/CNAME record for `hosts.auth` → your ingress LB IP, and open
`https://<hosts.auth>`.

> PodSecurity: `baseline`, not `restricted` — OnlyOffice doesn't currently
> pass `restricted`. Tighten per component later.

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

Components follow the `{{ .Release.Name }}-<component>` convention. The
canonical release name is **`shared`** — consumer charts (openfilz-ce,
openfilz-ee, the provisioner) embed these FQDNs:

| Service | FQDN | Port |
|---|---|---|
| Keycloak | `shared-keycloak.openfilz-shared.svc.cluster.local` | 8080 |
| OpenSearch | `shared-opensearch.openfilz-shared.svc.cluster.local` | 9200 |
| Gotenberg | `shared-gotenberg.openfilz-shared.svc.cluster.local` | 3000 |
| OnlyOffice | `shared-onlyoffice.openfilz-shared.svc.cluster.local` | 80 |

## Namespace-label contract (tenant access)

The NetworkPolicies default-deny ingress on the namespace, then allow:

- `kube-system` (ingress controller) → keycloak 8080 + onlyoffice 80 (the two
  ingress hosts);
- any namespace labelled **`openfilz.com/tenant-access: "true"`** → keycloak
  8080, opensearch 9200, gotenberg 3000, onlyoffice 80. With
  `networkPolicy.opensearchClientComponents` set, only those pods reach opensearch; with
  `onlyoffice.tenancy.enabled`, none reach onlyoffice directly.
- keycloak → keycloak-db 5432.

Every consuming namespace must carry the label:

```bash
kubectl label namespace demo-ce openfilz.com/tenant-access=true
```

Without the label, that namespace's API pods time out on Keycloak JWT
validation, full-text indexing and thumbnails.

## Replacing the Keycloak image

The default `images.keycloak` (`ghcr.io/openfilz/keycloak:26.7`, public, built
from `deploy/docker-compose/dokploy/keycloak/`) bakes the **realm export**
(the initial load) + the OpenFilz login/email themes, and its entrypoint
substitutes `KEYCLOAK_PUBLIC_URL` / `OPENFILZ_WEB_ROOT_URL` / the default
role+group variables into the realm at first import.

A customized Keycloak image (different baked realm, extra providers) can be
swapped in without any template change:

- `images.keycloak` → the replacement image, `images.pullSecrets` → pull
  secret(s) when it comes from a private registry;
- `keycloak.extraEnv` (map) / `keycloak.extraEnvSecretRef` (envFrom) → any
  image-specific env vars it needs.

The realm baked into the image only becomes the **initial load** on the very
first start against an empty keycloak_db — switching images later does NOT
re-import or migrate an existing realm. Pick the right image before the first
install.

## Realm per tenant/demo

The custom Keycloak image bakes the realm export + OpenFilz themes; `--import-realm`
imports it once as the **template** realm (URLs substituted from `KEYCLOAK_PUBLIC_URL`
/ `OPENFILZ_WEB_ROOT_URL` at import). Each demo/tenant then gets its **own realm**
(e.g. `demo-ce`, `cust-<prefix>`), created via the Keycloak Admin API from that
template, with client redirect URIs pointing at the tenant's web host. Consumer charts
set `auth.publicAuthority=https://<hosts.auth>/realms/<realm>` and
`auth.realmInternalUrl=http://shared-keycloak.openfilz-shared.svc.cluster.local:8080/realms/<realm>`.

## OnlyOffice notes

- `JWT_ENABLED/JWT_SECRET/JWT_HEADER=Authorization` + `ALLOW_META_IP_ADDRESS` /
  `ALLOW_PRIVATE_IP_ADDRESS` mirror the compose deployments.
- Behind Traefik, TLS is terminated at the Ingress and `X-Forwarded-Proto: https`
  is set by Traefik itself, so no middleware is templated. If OnlyOffice still
  generates `http://` asset URLs behind your setup, add a Traefik `Middleware`
  CR with `headers.customRequestHeaders.X-Forwarded-Proto: https` and reference
  it via `ingress.annotations`:
  `traefik.ingress.kubernetes.io/router.middlewares: openfilz-shared-<name>@kubernetescrd`.
- The image is pinned (`images.onlyoffice`). Re-test `onlyoffice.tenancy` before moving it.
- `Data`/logs PVCs are off by default (fonts/certs cache is rebuildable); enable via
  `onlyoffice.persistence.*.enabled` for production tenants.

### Several document servers (`onlyoffice.replicas`)

```bash
helm upgrade shared . -n openfilz-shared --reuse-values --set onlyoffice.replicas=3
```

Use helm rather than `kubectl scale`: the router's list of servers comes from this value. Each server asks for
`resources.onlyoffice` (2 GiB by default), and they spread over the nodes when possible.

The Community edition keeps a document's live editing state in the memory of the server that opened it, and
offers no way to share it. So these are not interchangeable servers behind a load balancer: above one replica, a
small router (`onlyoffice.router.replicas`, 2 by default) hashes the document key and sends everyone editing a
document to the same server. In practice:

- More capacity, and a failed server only affects its own documents.
- A document whose server goes away reopens on another one from its last saved version. Changes made
  since the last save on that server are lost, as with a single server.
- During that window a document can sit on two servers: someone who opened it on the stand-in stays there, and
  once the original server is back, new arrivals go to it. Each saves its own copy, so the last save wins.
  Only a server failure or a change of the number of servers can cause this.
- Changing the number of servers moves only the documents of the servers that come or go. Do it outside busy
  hours: those documents are affected like above.
- Replicas are independent: their volumes (`onlyoffice.persistence`) are per server.

### One server, one secret per deployment (`onlyoffice.tenancy`)

Without tenancy every consuming API holds the server's one JWT secret, so a compromised API can sign
editor tokens for all the others. With tenancy each deployment gets its own secret and its own address:

```bash
helm upgrade shared . -n openfilz-shared --reuse-values \
  --set onlyoffice.enabled=true --set onlyoffice.jwtSecret="$(openssl rand -base64 48)" \
  --set onlyoffice.tenancy.enabled=true \
  --set onlyoffice.tenancy.baseDomain=docs.example.com \
  --set onlyoffice.tenancy.tlsSecretName=wildcard-docs-example-com-tls
```

Prerequisites: a wildcard DNS record `*.docs.example.com` to the ingress, and a certificate for that
name in this namespace.

Adding a deployment `acme`, with a secret of at least 32 characters (a shorter one is padded by the API,
so it no longer matches the server's):

```bash
kubectl -n openfilz-shared patch secret shared-onlyoffice-tenants --type merge \
  -p "{\"data\":{\"acme\":\"$(printf %s "$SECRET" | base64 -w0)\"}}"
```

and on its API `ONLYOFFICE_URL=https://acme.docs.example.com` + `ONLYOFFICE_JWT_SECRET=$SECRET`. Editors
and save callbacks then use that address, and the server keeps each tenant's working files apart. A
tenant is live within about two minutes (a sidecar copies the Secret into the folders the server reads).
A changed or removed secret takes up to five more minutes (the server caches it). A host with no secret
refuses every token.

Limits:

- **The server logs `getTenantLicense error: missing "multitenancy" or "alias" field`** on each request.
  The Community image has no licence for this mode and only logs it: tested on 9.4.0.1, and the licence
  source is unchanged in 10.0.0.1. A later version could enforce it, so re-test before moving
  `images.onlyoffice`. If it does, the way back is `onlyoffice.tenancy.enabled=false` and one shared
  secret.
- Isolation is between secrets and working files. All tenants still share the process.
- Not available with `openshift.enabled` (no wildcard Route).

With tenancy on, tenant namespaces no longer reach the server directly (only through the ingress), and an
egress policy limits it to DNS, the `api` pods of tenant namespaces on 8080, and public addresses on 80 and
443. The DNS rule expects the cluster DNS pods to carry `k8s-app: kube-dns`.

## Not yet done (follow-ups)

- OnlyOffice Traefik middleware CR (only if X-Forwarded-Proto proves necessary).
- OpenSearch snapshots/backup; index-per-tenant hygiene jobs.
- Keycloak production hardening: HA (external Infinispan), PodDisruptionBudget,
  metrics scraping (management port 9000 is exposed on the Service already).
