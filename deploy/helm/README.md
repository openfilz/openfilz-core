# OpenFilz Helm charts (Community Edition)

Everything needed to run **OpenFilz CE on Kubernetes**. These charts are
Community Edition only — no enterprise component lives here. (Enterprise
deployments reuse them and add their own charts on top; see
"Deploying OpenFilz EE" below.)

| Chart | Purpose |
|---|---|
| [`openfilz-ce`](openfilz-ce/) | **Start here.** One OpenFilz instance in one namespace: api + web (pulled in as `file://` subcharts) + PostgreSQL + optional pg_dump backup CronJob + NetworkPolicies |
| [`openfilz-shared`](openfilz-shared/) | Deploy-once shared services consumed cross-namespace: Keycloak (+db), OpenSearch, Gotenberg, optional OnlyOffice |
| [`openfilz-api`](openfilz-api/) | CE API component chart — standalone use (bring your own PostgreSQL/Keycloak) or as an `openfilz-ce` subchart |
| [`openfilz-web`](openfilz-web/) | CE web UI component chart — standalone use or as an `openfilz-ce` subchart |

## Deploying OpenFilz CE — what actually runs

A complete CE deployment is **two Helm releases in two namespaces** (plus your
ingress controller and TLS certificates):

```
namespace openfilz-shared          namespace <your-app-ns> (label: openfilz.com/tenant-access=true)
┌─────────────────────────────┐    ┌──────────────────────────────────────────┐
│ shared-keycloak   (auth)    │◄───│ openfilz-api  ── openfilz-web            │
│ shared-keycloak-db          │    │      │                                   │
│ shared-gotenberg (thumbnails)│◄──│      └── <release>-postgres (documents DB)│
│ shared-opensearch (fulltext)│◄───│          PVC or S3/MinIO document storage│
│ shared-onlyoffice (optional)│◄───│                                          │
└─────────────────────────────┘    └──────────────────────────────────────────┘
        chart: openfilz-shared                 chart: openfilz-ce
        release name MUST be "shared"          one release per OpenFilz instance
```

Prerequisites: a Kubernetes cluster, an ingress controller (nginx, Traefik, …)
or OpenShift Routes, a TLS secret per ingress namespace (e.g. cert-manager),
and three DNS records → your ingress LB: the web host, the api host and the
auth host.

### Step 1 — shared services (once per cluster)

```bash
kubectl create namespace openfilz-shared
helm install shared ./openfilz-shared -n openfilz-shared \
  --set keycloak.adminPassword="$(openssl rand -base64 24)" \
  --set keycloak.db.password="$(openssl rand -base64 24)" \
  --set hosts.auth=auth.example.com \
  --set ingress.className=nginx --set ingress.tlsSecretName=my-tls
```

The release name `shared` is a contract: consumers resolve
`shared-keycloak.openfilz-shared.svc.cluster.local` etc. Skip this chart
entirely if you already run Keycloak (and don't need thumbnails/full-text) —
point openfilz-ce at your own services instead.

### Step 2 — the OpenFilz instance (once per instance/tenant)

```bash
kubectl create namespace openfilz
kubectl label namespace openfilz openfilz.com/tenant-access=true   # NetworkPolicy contract

helm dependency build ./openfilz-ce      # vendors ../openfilz-api + ../openfilz-web
helm install openfilz ./openfilz-ce -n openfilz \
  --set db.password="$(openssl rand -base64 24)" \
  --set global.hosts.web=app.example.com \
  --set global.hosts.api=api.example.com \
  --set global.ingress.className=nginx \
  --set global.ingress.tlsSecretName=my-tls \
  --set global.auth.publicAuthority=https://auth.example.com/realms/openfilz \
  --set global.auth.realmInternalUrl=http://shared-keycloak.openfilz-shared.svc.cluster.local:8080/realms/openfilz

kubectl -n openfilz get pods -w          # postgres → api (Flyway migrates) → web
```

For a quick Keycloak-less evaluation, add `--set global.auth.enabled=false`
and skip step 1 (thumbnails/full-text/OnlyOffice off).

Cross-cutting settings live once under `global:` (hosts, ingress, auth, db,
OnlyOffice, node scheduling, `imageTag`); api-specific settings (storage
local/minio, features, quotas, resources) under `openfilz-api:` — see each
chart's README and values.yaml.

## Deploying OpenFilz EE

The Enterprise stack **reuses the `openfilz-shared` chart** and replaces
`openfilz-ce` with the `openfilz-ee` chart (collaboration-api, web-ee, admin,
license-server, optional webhooks/archiving). Three EE-specific points, all
served from the openfilz-enterprise repository (`deploy/k3s/charts/`):

1. **The Keycloak image/config/initial load is different** — EE platforms
   apply the `values-keycloak-ee.yaml` overlay (next to the openfilz-shared-ee
   chart) on the `openfilz-shared` release **from the first install**: EE
   image (different baked realm), keycloak-events webhook to license-server,
   truststore. See "Keycloak: CE vs EE" in openfilz-shared's README.
2. `openfilz-shared-ee` — EE add-on installed **into the same
   `openfilz-shared` namespace** (ClamAV for antivirus-api).
3. `openfilz-ee` — the per-instance EE stack (needs a license bundle + access
   to the private ghcr images).

The full EE runbook is `openfilz-enterprise/deploy/k3s/INSTALL.md`.

## Releases

Released charts are published as OCI artifacts:
`oci://ghcr.io/openfilz/charts/<name>:<version>` (version stamped at package
time); `openfilz-ce` and `openfilz-shared` are public.
