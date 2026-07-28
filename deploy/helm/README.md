# OpenFilz Community Edition Helm charts

| Chart | Purpose |
|---|---|
| [`openfilz-api`](openfilz-api/) | CE API (REST + GraphQL) — standalone or as an `openfilz-ce` subchart |
| [`openfilz-web`](openfilz-web/) | CE web UI (Angular/nginx) — standalone or as an `openfilz-ce` subchart |
| [`openfilz-ce`](openfilz-ce/) | Umbrella: api + web (as `file://` dependencies) + PostgreSQL + backup CronJob + NetworkPolicies, for one namespace |
| [`openfilz-shared`](openfilz-shared/) | Shared services consumed cross-namespace: Keycloak (+db), OpenSearch, Gotenberg, ClamAV, optional OnlyOffice |

The component charts read cross-cutting settings from the Helm `global:` block
so the umbrella can wire hosts/ingress/auth/db/OnlyOffice once — standalone
installs just set the chart-local keys instead. `global.ce.enabled=true` (set
by `openfilz-ce`) switches the api chart's conventions to the umbrella's
PostgreSQL (`<release>-postgres`) and DB secret (`<release>-db`).

## Quick start

```bash
cd openfilz-ce
helm dependency build .        # vendors ../openfilz-api + ../openfilz-web
helm install openfilz . -n openfilz --create-namespace \
  --set db.password="$(openssl rand -base64 24)" \
  --set global.hosts.web=app.example.com \
  --set global.hosts.api=api.example.com \
  --set global.ingress.className=nginx \
  --set global.ingress.tlsSecretName=my-tls \
  --set global.auth.publicAuthority=https://auth.example.com/realms/openfilz \
  --set global.auth.realmInternalUrl=http://keycloak:8080/realms/openfilz
```

(For a Keycloak-less evaluation add `--set global.auth.enabled=false`.)

Released charts are published as OCI artifacts to
`oci://ghcr.io/openfilz/charts/<name>:<version>` (version stamped at package
time); `openfilz-ce` and `openfilz-shared` are public. The enterprise
counterparts (`openfilz-ee`, `openfilz-mgmt`) live in the openfilz-enterprise
repository under `deploy/k3s/charts/`, which also hosts the k3s cluster
runbook (`deploy/k3s/INSTALL.md`) and the chart release script.
