{{- define "openfilz-ce.labels" -}}
app.kubernetes.io/part-of: openfilz-ce
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "openfilz-ce.dbSecretName" -}}
{{- if .Values.global.db.existingSecret }}{{ .Values.global.db.existingSecret }}{{ else }}{{ .Release.Name }}-db{{ end }}
{{- end }}
