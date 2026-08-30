{{/* Chart name (nameOverride-aware) */}}
{{- define "openfilz-api.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{/* Fully qualified resource name */}}
{{- define "openfilz-api.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end }}

{{- define "openfilz-api.selectorLabels" -}}
app.kubernetes.io/name: {{ include "openfilz-api.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/* The `component: api` label is a contract with the openfilz-ce NetworkPolicies. */}}
{{- define "openfilz-api.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "openfilz-api.selectorLabels" . }}
app.kubernetes.io/component: api
app.kubernetes.io/part-of: openfilz
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/* ---- global-aware getters (chart-local value wins, then global, then default) ---- */}}

{{- define "openfilz-api.ceEnabled" -}}
{{- dig "ce" "enabled" false (.Values.global | default dict) -}}
{{- end }}

{{- define "openfilz-api.imageTag" -}}
{{- coalesce .Values.image.tag (dig "imageTag" "" (.Values.global | default dict)) .Chart.AppVersion -}}
{{- end }}

{{- define "openfilz-api.hostApi" -}}
{{- coalesce .Values.hosts.api (dig "hosts" "api" "" (.Values.global | default dict)) | default "" -}}
{{- end }}

{{- define "openfilz-api.hostWeb" -}}
{{- coalesce .Values.hosts.web (dig "hosts" "web" "" (.Values.global | default dict)) | default "" -}}
{{- end }}

{{- define "openfilz-api.publicApiBaseUrl" -}}
{{- if .Values.urls.publicApiBaseUrl -}}
{{- .Values.urls.publicApiBaseUrl -}}
{{- else -}}
{{- with (include "openfilz-api.hostApi" .) }}https://{{ . }}{{ end -}}
{{- end -}}
{{- end }}

{{- define "openfilz-api.publicWebBaseUrl" -}}
{{- if .Values.urls.publicWebBaseUrl -}}
{{- .Values.urls.publicWebBaseUrl -}}
{{- else -}}
{{- with (include "openfilz-api.hostWeb" .) }}https://{{ . }}{{ end -}}
{{- end -}}
{{- end }}

{{- define "openfilz-api.authEnabled" -}}
{{- if not (kindIs "invalid" .Values.auth.enabled) -}}
{{- .Values.auth.enabled -}}
{{- else -}}
{{- dig "auth" "enabled" true (.Values.global | default dict) -}}
{{- end -}}
{{- end }}

{{- define "openfilz-api.realmInternalUrl" -}}
{{- coalesce .Values.auth.realmInternalUrl (dig "auth" "realmInternalUrl" "" (.Values.global | default dict)) | default "" -}}
{{- end }}

{{/* Public realm URL advertised in MCP OAuth discovery: chart-local value, then
the umbrella's browser-facing issuer (global.auth.publicAuthority). */}}
{{- define "openfilz-api.mcpAuthorizationServerUrl" -}}
{{- coalesce .Values.mcp.authorizationServerUrl (dig "auth" "publicAuthority" "" (.Values.global | default dict)) | default "" -}}
{{- end }}

{{- define "openfilz-api.corsAllowedOrigins" -}}
{{- if .Values.auth.corsAllowedOrigins -}}
{{- .Values.auth.corsAllowedOrigins -}}
{{- else -}}
{{- include "openfilz-api.publicWebBaseUrl" . -}}
{{- end -}}
{{- end }}

{{- define "openfilz-api.onlyofficeEnabled" -}}
{{- if not (kindIs "invalid" .Values.onlyoffice.enabled) -}}
{{- .Values.onlyoffice.enabled -}}
{{- else -}}
{{- dig "onlyoffice" "enabled" false (.Values.global | default dict) -}}
{{- end -}}
{{- end }}

{{- define "openfilz-api.onlyofficeInternalUrl" -}}
{{- coalesce .Values.onlyoffice.internalUrl (dig "onlyoffice" "internalUrl" "" (.Values.global | default dict)) | default "" -}}
{{- end }}

{{- define "openfilz-api.onlyofficeJwtSecret" -}}
{{- coalesce .Values.onlyoffice.jwtSecret (dig "onlyoffice" "jwtSecret" "" (.Values.global | default dict)) | default "" -}}
{{- end }}

{{/* ---- database wiring ---- */}}

{{- define "openfilz-api.dbHost" -}}
{{- if .Values.database.host -}}
{{- .Values.database.host -}}
{{- else if eq (include "openfilz-api.ceEnabled" .) "true" -}}
{{- printf "%s-postgres" .Release.Name -}}
{{- else -}}
localhost
{{- end -}}
{{- end }}

{{- define "openfilz-api.dbName" -}}
{{- coalesce .Values.database.name (dig "db" "name" "" (.Values.global | default dict)) "dms_db" -}}
{{- end }}

{{- define "openfilz-api.dbUser" -}}
{{- coalesce .Values.database.user (dig "db" "user" "" (.Values.global | default dict)) "dms_user" -}}
{{- end }}

{{/*
Secret holding the DB password:
1. database.existingSecret (chart-local, keys per database.secretKeys)
2. global.db.existingSecret (umbrella convention, key postgres-password)
3. <release>-db when running under openfilz-ce (created by the umbrella)
4. <fullname>-db-credentials (created by this chart — standalone)
*/}}
{{- define "openfilz-api.dbSecretName" -}}
{{- $globalSecret := dig "db" "existingSecret" "" (.Values.global | default dict) -}}
{{- if .Values.database.existingSecret -}}
{{- .Values.database.existingSecret -}}
{{- else if $globalSecret -}}
{{- $globalSecret -}}
{{- else if eq (include "openfilz-api.ceEnabled" .) "true" -}}
{{- printf "%s-db" .Release.Name -}}
{{- else -}}
{{- printf "%s-db-credentials" (include "openfilz-api.fullname" .) -}}
{{- end -}}
{{- end }}

{{- define "openfilz-api.dbPasswordKey" -}}
{{- $globalSecret := dig "db" "existingSecret" "" (.Values.global | default dict) -}}
{{- if .Values.database.existingSecret -}}
{{- .Values.database.secretKeys.password | default "password" -}}
{{- else if or $globalSecret (eq (include "openfilz-api.ceEnabled" .) "true") -}}
postgres-password
{{- else -}}
password
{{- end -}}
{{- end }}

{{/* ---- storage / ingress ---- */}}

{{- define "openfilz-api.storageClaimName" -}}
{{- .Values.storage.persistence.existingClaim | default (printf "%s-storage" (include "openfilz-api.fullname" .)) -}}
{{- end }}

{{- define "openfilz-api.ingressEnabled" -}}
{{- if not (kindIs "invalid" .Values.ingress.enabled) -}}
{{- .Values.ingress.enabled -}}
{{- else -}}
{{- dig "ingress" "enabled" true (.Values.global | default dict) -}}
{{- end -}}
{{- end }}

{{- define "openfilz-api.ingressClassName" -}}
{{- coalesce .Values.ingress.className (dig "ingress" "className" "" (.Values.global | default dict)) | default "" -}}
{{- end }}

{{- define "openfilz-api.ingressTlsSecret" -}}
{{- coalesce .Values.ingress.tlsSecretName (dig "ingress" "tlsSecretName" "" (.Values.global | default dict)) | default "" -}}
{{- end }}
