variable "namespace" {
  description = "Namespace the platform runs in. Must equal the GitHub environment's K8S_NAMESPACE (deploy-services.yml)."
  type        = string
}

variable "monitoring_namespace" {
  description = "Namespace Prometheus runs in; it may scrape the deployables."
  type        = string
  default     = "monitoring"
}

variable "quota" {
  description = "ResourceQuota for the namespace. Defaults fit five deployables at the production autoscaling maximum (20 replicas, 500m/768Mi requests, 2/1536Mi limits) with headroom for rolling updates."
  type        = map(string)
  default = {
    "requests.cpu"    = "64"
    "requests.memory" = "96Gi"
    "limits.cpu"      = "220"
    "limits.memory"   = "170Gi"
    "pods"            = "130"
    "services"        = "20"
  }
}

variable "labels" {
  description = "Extra labels to add to the namespace"
  type        = map(string)
  default     = {}
}
