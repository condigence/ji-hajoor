output "namespace_name" {
  description = "The namespace the platform runs in (set the GitHub environment's K8S_NAMESPACE to this)"
  value       = kubernetes_namespace.this.metadata[0].name
}
