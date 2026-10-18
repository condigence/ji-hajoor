{{/*
Service name. Required: every release must say which service it is.
*/}}
{{- define "jihajoor-service.name" -}}
{{- $svc := required "serviceName is required (set it in helm/services/<service>.yaml)" .Values.serviceName -}}
{{- default $svc .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Resource name. Deliberately the bare service name (not "<release>-<name>"), so
in-cluster DNS is <service>:<port> exactly as in docker-compose.core.yml and the
services' built-in URL defaults resolve without extra wiring.
*/}}
{{- define "jihajoor-service.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- include "jihajoor-service.name" . -}}
{{- end -}}
{{- end -}}

{{/*
"true" when KEDA scales this deployable: enabled for the environment and the deployable lists
Kafka triggers. Empty otherwise, so it can be used directly in an if.
*/}}
{{- define "jihajoor-service.kedaActive" -}}
{{- if and .Values.keda.enabled .Values.keda.triggers -}}true{{- end -}}
{{- end -}}

{{/*
Chart name and version label.
*/}}
{{- define "jihajoor-service.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Port the service listens on.
*/}}
{{- define "jihajoor-service.port" -}}
{{- required "containerPort is required (set it in helm/services/<service>.yaml)" .Values.containerPort | int -}}
{{- end -}}

{{/*
Common labels applied to every resource.
*/}}
{{- define "jihajoor-service.labels" -}}
helm.sh/chart: {{ include "jihajoor-service.chart" . }}
{{ include "jihajoor-service.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | trunc 63 | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: jihajoor
environment: {{ required "environment is required (values-staging.yaml or values-production.yaml)" .Values.environment | quote }}
{{- end -}}

{{/*
Selector labels — stable across upgrades.
*/}}
{{- define "jihajoor-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "jihajoor-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app: {{ include "jihajoor-service.name" . }}
{{- end -}}

{{/*
ServiceAccount name to use.
*/}}
{{- define "jihajoor-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "jihajoor-service.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/*
Name of the existing Secret the service reads credentials from.
*/}}
{{- define "jihajoor-service.secretName" -}}
{{- default (printf "%s-secrets" (include "jihajoor-service.fullname" .)) .Values.secrets.name -}}
{{- end -}}
