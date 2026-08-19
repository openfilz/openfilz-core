# OpenFilz Helm charts

Everything needed to run **OpenFilz on Kubernetes**.

| Chart | Purpose |
|---|---|
| [`openfilz-ce`](openfilz-ce/) | **Start here.** One OpenFilz instance in one namespace: api + web (pulled in as `file://` subcharts) + PostgreSQL + optional pg_dump backup CronJob + NetworkPolicies |
| [`openfilz-shared`](openfilz-shared/) | Deploy-once shared services consumed cross-namespace: Keycloak (+db), OpenSearch, Gotenberg, optional OnlyOffice |
| [`openfilz-api`](openfilz-api/) | CE API component chart — standalone use (bring your own PostgreSQL/Keycloak) or as an `openfilz-ce` subchart |
| `openfilz-web` | Web UI component chart — lives in the **openfilz-web repository** (`deploy/helm/openfilz-web`), released with the web app; the umbrella pulls it from `oci://ghcr.io/openfilz/charts` |

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

helm dependency build ./openfilz-ce      # vendors ../openfilz-api + pulls openfilz-web from ghcr
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

## OpenShift notes

Both installable charts support OpenShift out of the box — see
`openfilz-shared/example-values/values-openshift.yaml` and
`openfilz-ce/example-values/values-openshift.yaml` for complete, commented
value sets. The four things that differ from vanilla Kubernetes:

1. **Routes instead of Ingress** — set `openshift.enabled=true`
   (openfilz-shared) and `openfilz-api.openshift.*` / `openfilz-web.openshift.*`
   (openfilz-ce); the Ingress templates are skipped automatically. Route hosts
   come from the usual `hosts.*` / `global.hosts.*` values.
2. **SCCs and UIDs** — several images (PostgreSQL, OpenSearch, OnlyOffice, the
   web nginx entrypoint) need a specific UID or root, which the default
   `restricted` SCC forbids. Grant an SCC (`anyuid` per ServiceAccount in
   production; `privileged` on `default` as a lab shortcut) **and** set the
   per-workload `podSecurityContext.runAsUser` values from the example files —
   OpenShift picks the least-privileged SCC that validates the pod, so a pod
   that requests nothing still lands on `restricted` with a random UID.
3. **NetworkPolicies** — set `networkPolicy.ingressControllerNamespace:
   openshift-ingress` *and* `networkPolicy.ingressFromNamespaceLabels` with the
   policy-group entries (see example values): OpenShift routers are often
   host-networked, and their traffic only matches the
   `policy-group.network.openshift.io/*` pseudo-namespaces — with only the
   namespace-name selector the routes time out even though they exist.
4. **StorageClass** — all `storageClassName` values default to empty = the
   cluster's default StorageClass; set them explicitly if your cluster has no
   default.

(The `OPENFILZ_FULLTEXT_OPENSEARCH_HOST/_PORT/_SCHEME` env vars injected for
`features.fulltext` are the same names the production compose uses — Spring's
relaxed binding maps them onto `openfilz.full-text.opensearch.*`.)

## Releases

Every backend release publishes all four charts as OCI artifacts —
`oci://ghcr.io/openfilz/charts/<name>:<version>` — from the
`publish-helm-charts` job in `.github/workflows/release-backend.yml`. The
chart version and appVersion are stamped with the release version at package
time (appVersion is the default image tag, so the api chart X.Y.Z runs
`openfilz-api:X.Y.Z`), alongside the images pushed by the same pipeline. The
`openfilz-web` chart follows the same rule but from the **openfilz-web
repository's** release workflow (web chart X.Y.Z runs `openfilz-web:X.Y.Z`);
each core release of the umbrella embeds the latest published 1.x web chart.
Install directly from the registry, e.g.:

```bash
helm install openfilz oci://ghcr.io/openfilz/charts/openfilz-ce --version <X.Y.Z> \
  -n openfilz --create-namespace [ --set ... ]
```

(OCI packages embed their dependencies — no `helm dependency build` needed;
that step is only for installs from a source checkout.)

**Cross-repo `global:` contract.** The umbrella injects shared settings into
the openfilz-web subchart through Helm globals (`global.hosts.*`,
`global.ingress.*`, `global.auth.*`, `global.onlyoffice.*`,
`global.imageTag`, `global.nodeSelector`/`tolerations`). The web chart lives
in the openfilz-web repository — don't rename these keys on either side
without coordinating the two repos.
