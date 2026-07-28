{{/* Chart name (nameOverride-aware) */}}
{{- define "openfilz-web.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{/* Fully qualified resource name */}}
{{- define "openfilz-web.fullname" -}}
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

{{- define "openfilz-web.selectorLabels" -}}
app.kubernetes.io/name: {{ include "openfilz-web.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/* The `component: web` label is a contract with the openfilz-ce NetworkPolicies. */}}
{{- define "openfilz-web.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "openfilz-web.selectorLabels" . }}
app.kubernetes.io/component: web
app.kubernetes.io/part-of: openfilz
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/* ---- global-aware getters (chart-local value wins, then global, then default) ---- */}}

{{- define "openfilz-web.imageTag" -}}
{{- coalesce .Values.image.tag (dig "imageTag" "" (.Values.global | default dict)) .Chart.AppVersion -}}
{{- end }}

{{- define "openfilz-web.hostWeb" -}}
{{- coalesce .Values.hosts.web (dig "hosts" "web" "" (.Values.global | default dict)) | default "" -}}
{{- end }}

{{- define "openfilz-web.hostApi" -}}
{{- coalesce .Values.hosts.api (dig "hosts" "api" "" (.Values.global | default dict)) | default "" -}}
{{- end }}

{{- define "openfilz-web.apiUrl" -}}
{{- if .Values.app.apiUrl -}}
{{- .Values.app.apiUrl -}}
{{- else -}}
{{- with (include "openfilz-web.hostApi" .) }}https://{{ . }}/api/v1{{ end -}}
{{- end -}}
{{- end }}

{{- define "openfilz-web.graphQlUrl" -}}
{{- if .Values.app.graphQlUrl -}}
{{- .Values.app.graphQlUrl -}}
{{- else -}}
{{- with (include "openfilz-web.hostApi" .) }}https://{{ . }}/graphql/v1{{ end -}}
{{- end -}}
{{- end }}

{{- define "openfilz-web.authEnabled" -}}
{{- if not (kindIs "invalid" .Values.auth.enabled) -}}
{{- .Values.auth.enabled -}}
{{- else -}}
{{- dig "auth" "enabled" true (.Values.global | default dict) -}}
{{- end -}}
{{- end }}

{{- define "openfilz-web.authPublicAuthority" -}}
{{- coalesce .Values.auth.publicAuthority (dig "auth" "publicAuthority" "" (.Values.global | default dict)) | default "" -}}
{{- end }}

{{- define "openfilz-web.authClientId" -}}
{{- coalesce .Values.auth.clientId (dig "auth" "clientId" "" (.Values.global | default dict)) "openfilz-web" -}}
{{- end }}

{{- define "openfilz-web.onlyofficeEnabled" -}}
{{- if not (kindIs "invalid" .Values.onlyoffice.enabled) -}}
{{- .Values.onlyoffice.enabled -}}
{{- else -}}
{{- dig "onlyoffice" "enabled" false (.Values.global | default dict) -}}
{{- end -}}
{{- end }}

{{- define "openfilz-web.onlyofficePublicUrl" -}}
{{- coalesce .Values.onlyoffice.publicUrl (dig "onlyoffice" "publicUrl" "" (.Values.global | default dict)) | default "" -}}
{{- end }}

{{- define "openfilz-web.ingressEnabled" -}}
{{- if not (kindIs "invalid" .Values.ingress.enabled) -}}
{{- .Values.ingress.enabled -}}
{{- else -}}
{{- dig "ingress" "enabled" true (.Values.global | default dict) -}}
{{- end -}}
{{- end }}

{{- define "openfilz-web.ingressClassName" -}}
{{- coalesce .Values.ingress.className (dig "ingress" "className" "" (.Values.global | default dict)) | default "" -}}
{{- end }}

{{- define "openfilz-web.ingressTlsSecret" -}}
{{- coalesce .Values.ingress.tlsSecretName (dig "ingress" "tlsSecretName" "" (.Values.global | default dict)) | default "" -}}
{{- end }}
