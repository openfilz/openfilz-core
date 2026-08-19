# openfilz-ce Helm chart

CE stack (web + api + PostgreSQL) for **one namespace**. Used for the `demo-ce`
namespace and as the template for per-tenant Essential installs. Shared services
(Keycloak, Gotenberg, OpenSearch, OnlyOffice) come from an
[`openfilz-shared`](../openfilz-shared/) release — this chart only points at them.

Since 0.2.0 this is an **umbrella chart**: the api and web workloads are the
[`openfilz-api`](../openfilz-api/) component chart (a `file://` dependency)
and the `openfilz-web` chart (published from the **openfilz-web repository**
to `oci://ghcr.io/openfilz/charts`, tracked with a `1.x.x` range so each
umbrella release embeds the latest web release), and this chart only adds
PostgreSQL, the DB secret, the optional pg_dump backup CronJob and the
namespace NetworkPolicies. Cross-cutting settings (hosts, ingress, auth, db,
OnlyOffice, scheduling) live under `global:`; component-specific settings live
under the `openfilz-api:` / `openfilz-web:` subchart keys.

## Install (demo-ce)

```bash
# prerequisites: a Kubernetes cluster with an ingress controller, a wildcard
# TLS secret synced into the namespace (e.g. via cert-manager), and a demo-ce
# realm created in the shared Keycloak.
kubectl create namespace demo-ce
kubectl label namespace demo-ce pod-security.kubernetes.io/enforce=restricted

helm dependency build .        # vendors ../openfilz-api + pulls openfilz-web from ghcr

helm install demo-ce . -n demo-ce \
  --set db.password="$(openssl rand -base64 24)" \
  --set global.hosts.web=ce-demo.openfilz.com \
  --set global.hosts.api=ce-demo-api.openfilz.com \
  --set global.auth.publicAuthority=https://auth-demo.openfilz.com/realms/demo-ce \
  --set global.auth.realmInternalUrl=http://shared-keycloak.openfilz-shared.svc.cluster.local:8080/realms/demo-ce

kubectl -n demo-ce get pods -w      # postgres → api (Flyway migrates) → web
```

Then add the two Cloudflare A/CNAME records → the LB IP (created by the CCM for
Traefik), and open https://ce-demo.openfilz.com.

## Per-tenant (Essential) differences vs demo

- `openfilz-api.storage.type=minio` + a per-tenant **Scaleway SSE bucket**
  (managed storage is always Scaleway SSE — see the study doc); move the minio
  keys to a Secret (`openfilz-api.storage.minio.existingSecret`, keys
  `access-key`/`secret-key`) before productionizing.
- `openfilz-api.features.fulltext=true` (index-per-tenant on the shared
  OpenSearch) and optionally `global.onlyoffice.enabled=true` (shared
  OnlyOffice, JWT per tenant).
- One Keycloak realm per tenant on the shared Keycloak.
- ResourceQuota + LimitRange on the namespace (provisioner applies them).
- `backup.enabled=true` + `backup.s3.*` (per-tenant Scaleway backup bucket +
  scoped key, set by the provisioner): nightly `pg_dump -Fc` CronJob → S3,
  pruned after `backup.retentionDays`. Documents need no extra backup
  (versioned document bucket).

## Upgrading from 0.1.x

0.2.0 restructures the values and renames the api/web resources:

| 0.1.x | 0.2.0 |
|---|---|
| `hosts.*`, `ingress.*`, `auth.*` (flat) | `global.hosts.*`, `global.ingress.*`, `global.auth.*` (`auth.clientId` → `global.auth.clientId`) |
| `db.name` / `db.user` / `db.existingSecret` | `global.db.*` (`db.password`, `db.storage`, `db.storageClassName` stay) |
| `images.api` / `images.web` / `images.pullPolicy` | `openfilz-api.image.*` / `openfilz-web.image.*` (or `global.imageTag` to pin both tags) |
| `storage.*`, `features.*`, `shared.gotenbergUrl`, `shared.opensearch*`, `quotas.*` | under `openfilz-api:` (`storage.size/storageClassName` → `storage.persistence.*`, `shared.*` → `gotenberg.url` / `opensearch.*`) |
| `features.onlyoffice` + `shared.onlyoffice*` | `global.onlyoffice.*` |
| `resources.api` / `resources.web` | `openfilz-api.resources` / `openfilz-web.resources` |
| `nodeSelector` / `tolerations` | `global.nodeSelector` / `global.tolerations` |

Workload/service names change from `<release>-api` / `<release>-web` to
`<release>-openfilz-api` / `<release>-openfilz-web`, and the document PVC from
`<release>-storage` to `<release>-openfilz-api-storage`. The PostgreSQL
StatefulSet, its PVC and the `<release>-db` secret are **unchanged** (no data
migration). To keep an existing document PVC, set
`openfilz-api.storage.persistence.existingClaim=<release>-storage`.

## Not yet done (follow-ups)

- api/web PodDisruptionBudgets; HPA for api.
