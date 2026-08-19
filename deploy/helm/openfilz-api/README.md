# openfilz-api Helm chart

Deploys the OpenFilz Community Edition API (REST + GraphQL) on Kubernetes or
OpenShift. Env names mirror `deploy/docker-compose/dokploy/compose.yaml` (the
authoritative CE deployment) — keep them in sync when the compose evolves.

Two ways to use it:

- **Standalone** — bring your own PostgreSQL (and Keycloak unless
  `auth.enabled=false`). This chart creates the Deployment, Service,
  Ingress/Route, storage PVC and (standalone only) the DB-credentials Secret.
- **As a subchart of [`openfilz-ce`](../openfilz-ce/)** — the umbrella chart
  adds PostgreSQL + backup + NetworkPolicies and wires the conventions through
  the `global:` values block (`global.ce.enabled=true` → DB host defaults to
  `<release>-postgres`, DB secret to `<release>-db`).

## Install (standalone)

```bash
helm install my-api . \
  --namespace my-namespace --create-namespace \
  --set database.host=my-postgres.my-namespace.svc.cluster.local \
  --set database.password="$(openssl rand -base64 24)" \
  --set hosts.api=api.example.com \
  --set hosts.web=app.example.com \
  --set ingress.className=nginx \
  --set auth.realmInternalUrl=https://auth.example.com/realms/openfilz
```

Example values: [`example-values/values-kind.yaml`](example-values/values-kind.yaml)
(local kind: no auth, hostPath PV, path-based nginx routing) and
[`example-values/values-oc.yaml`](example-values/values-oc.yaml) (OpenShift Route).

## Key values

| Value | Description | Default |
| :--- | :--- | :--- |
| `image.registry/repository/tag` | Image; empty tag → `global.imageTag` → chart appVersion | `ghcr.io/openfilz/openfilz-api:latest` |
| `hosts.api` / `hosts.web` | Public hostnames (ingress host, public URLs, CORS) | `""` (→ `global.hosts.*`) |
| `urls.publicApiBaseUrl` / `urls.publicWebBaseUrl` | Override the `https://<host>` derivation | derived |
| `auth.enabled` | `false` → `OPENFILZ_SECURITY_NO_AUTH=true` (dev/eval only) | `true` (via global) |
| `auth.realmInternalUrl` | `KEYCLOAK_REALM_URL` for JWT validation | `""` |
| `database.host/port/name/user` | PostgreSQL connection (`DB_*` env) | `localhost:5432/dms_db` |
| `database.password` | Password for the chart-created secret (standalone) — **set it**, empty = random at every upgrade | `""` |
| `database.existingSecret` + `database.secretKeys.*` | Bring-your-own credentials secret | `""` |
| `storage.type` | `local` (PVC) or `minio` (S3) | `local` |
| `storage.persistence.*` | PVC size/class, `existingClaim`, `hostPath` (kind/dev PV) | `10Gi` |
| `storage.minio.*` | Endpoint/bucket + keys or `existingSecret` (keys `access-key`/`secret-key`) | `""` |
| `features.*` | `thumbnails` (needs `gotenberg.url`), `softDelete`, `checksum`, `fulltext` (needs `opensearch.*`) | see values |
| `onlyoffice.*` | OnlyOffice integration (enabled/internalUrl/jwtSecret) | disabled |
| `quotas.fileUploadMb` / `quotas.userMb` | Upload/user quotas (MB, 0 = unlimited) | `1024` / `2048` |
| `ingress.*` | className, `tlsSecretName`, `paths`, `swagger.enabled` | enabled, `/` |
| `openshift.enabled` + `openshift.route.*` | OpenShift Route instead of Ingress | disabled |
| `extraEnv` / `extraEnvSecretRef` | Arbitrary extra env vars (ConfigMap / existing Secret via `envFrom`) | `{}` / `""` |
| `podSecurityContext` | Defaults to the Paketo image uid/gid (1002/1000, fsGroup 1000) | see values |

## Migrating from chart 1.0.x

The 1.0.x chart (bitnami/common based) was replaced in 1.1.0:

- No more `helm dependency update` / bitnami repo — the chart is dependency-free.
- The chart-created DB secret now stores only `password` (`DB_USER` comes from
  `database.user` unless `database.secretKeys.user` points into a secret).
- `persistence.*` moved to `storage.persistence.*`; the PVC is named
  `<fullname>-storage` (use `storage.persistence.existingClaim` to keep an old claim).
- `api.port` now defaults to 8080 and the Service port to 8080.
- The Ingress is host-based when `hosts.api` is set (path-only rules remain the
  fallback when it is empty).
