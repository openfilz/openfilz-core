{{- define "openfilz-shared.labels" -}}
app.kubernetes.io/part-of: openfilz-shared
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "openfilz-shared.keycloakSecretName" -}}
{{- if .Values.keycloak.existingSecret }}{{ .Values.keycloak.existingSecret }}{{ else }}{{ .Release.Name }}-keycloak{{ end }}
{{- end }}

{{- define "openfilz-shared.onlyofficeSecretName" -}}
{{- if .Values.onlyoffice.existingSecret }}{{ .Values.onlyoffice.existingSecret }}{{ else }}{{ .Release.Name }}-onlyoffice{{ end }}
{{- end }}
