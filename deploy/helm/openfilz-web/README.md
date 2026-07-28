# openfilz-web Helm chart

Deploys the OpenFilz Community Edition web UI (Angular, served by nginx) on
Kubernetes or OpenShift. The container generates `ngx-env.js` at startup from
the `NG_APP_*` environment variables — the same mechanism as the compose
deployment.

Two ways to use it:

- **Standalone** — point it at an existing openfilz-api (`hosts.api` or
  `app.apiUrl`) and Keycloak (`auth.publicAuthority`).
- **As a subchart of [`openfilz-ce`](../openfilz-ce/)** — the umbrella chart
  wires hosts/auth/OnlyOffice through the `global:` values block.

## Install (standalone)

```bash
helm install my-web . \
  --namespace my-namespace --create-namespace \
  --set hosts.web=app.example.com \
  --set hosts.api=api.example.com \
  --set ingress.className=nginx \
  --set auth.publicAuthority=https://auth.example.com/realms/openfilz
```

Example values: [`example-values/values-kind.yaml`](example-values/values-kind.yaml)
(local kind, no auth) and [`example-values/values-oc.yaml`](example-values/values-oc.yaml)
(OpenShift Route).

## Key values

| Value | Description | Default |
| :--- | :--- | :--- |
| `image.registry/repository/tag` | Image; empty tag → `global.imageTag` → chart appVersion | `ghcr.io/openfilz/openfilz-web:latest` |
| `hosts.web` / `hosts.api` | Public hostnames (ingress host + API URL derivation) | `""` (→ `global.hosts.*`) |
| `app.apiUrl` / `app.graphQlUrl` | Override the `https://<hosts.api>/...` derivation | derived |
| `auth.enabled` | `NG_APP_AUTHENTICATION_ENABLED` | `true` (via global) |
| `auth.publicAuthority` | Browser-facing issuer (must match the token `iss` claim) | `""` |
| `auth.clientId` | OIDC client id | `openfilz-web` |
| `onlyoffice.enabled` / `onlyoffice.publicUrl` | OnlyOffice editor integration | disabled |
| `ingress.*` | className, `tlsSecretName`, `path`, annotations | enabled, `/` |
| `openshift.enabled` + `openshift.route.*` | OpenShift Route instead of Ingress | disabled |
| `extraEnv` | Extra `NG_APP_*` env vars via a chart-managed ConfigMap | `{}` |

## Migrating from chart 1.0.x

The 1.0.x chart (bitnami/common based) was replaced in 1.1.0: no more
`helm dependency update` / bitnami repo, the Service port defaults to 8080,
and auth/OnlyOffice runtime settings are first-class values instead of
requiring template edits.
