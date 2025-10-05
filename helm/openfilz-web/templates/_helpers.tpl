{{/*
Expand the name of the chart.
This is the base name used by other helpers. It defaults to the chart name
and can be overridden by the 'nameOverride' value.
*/}}
{{- define "openfilz-api.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "openfilz-api.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Common helpers from bitnami/common are used for fullname, labels, etc.
*/}}
{{- include "common.names.fullname" . -}}
{{- include "common.labels.standard" . -}}
{{- include "common.labels.matchLabels" . -}}