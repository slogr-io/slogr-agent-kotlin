{{/*
Expand the name of the chart.
*/}}
{{- define "slogr-agent.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "slogr-agent.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "slogr-agent.labels" -}}
helm.sh/chart: {{ include "slogr-agent.chart" . }}
{{ include "slogr-agent.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "slogr-agent.selectorLabels" -}}
app.kubernetes.io/name: {{ include "slogr-agent.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Chart label
*/}}
{{- define "slogr-agent.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Service account name
*/}}
{{- define "slogr-agent.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "slogr-agent.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Name of the Secret containing the API key.
Uses existingSecret if provided, otherwise uses the chart-managed secret.
*/}}
{{- define "slogr-agent.secretName" -}}
{{- if .Values.slogr.existingSecret }}
{{- .Values.slogr.existingSecret }}
{{- else }}
{{- printf "%s-api-key" (include "slogr-agent.fullname" .) }}
{{- end }}
{{- end }}
