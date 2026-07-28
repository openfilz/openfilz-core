# OpenFilz Helm charts

Everything needed to run **OpenFilz on Kubernetes**.

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

## Releases

Every backend release publishes all four charts as OCI artifacts —
`oci://ghcr.io/openfilz/charts/<name>:<version>` — from the
`publish-helm-charts` job in `.github/workflows/release-backend.yml`. The
chart version and appVersion are stamped with the release version at package
time (appVersion is the default image tag, so the api chart X.Y.Z runs
`openfilz-api:X.Y.Z`), alongside the images pushed by the same pipeline. The
one exception is `openfilz-web`: its images are released from the
openfilz-web repository on their own version stream, so the chart keeps
appVersion `latest` — set `openfilz-web.image.tag` (or the chart-local
`image.tag`) to pin a specific web release. Install directly from the
registry, e.g.:

```bash
helm install openfilz oci://ghcr.io/openfilz/charts/openfilz-ce --version <X.Y.Z> \
  -n openfilz --create-namespace [ --set ... ]
```

(OCI packages embed their dependencies — no `helm dependency build` needed;
that step is only for installs from a source checkout.)
