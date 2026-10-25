##############################################################
# Kubernetes Namespace Module — Ji-Hajoor
#
# Creates the ONE namespace the platform runs in for this environment. The 20 services run as
# five deployables (api-gateway, identity-app, jobs-app, money-app, engagement-app), each a
# Helm release of helm/jihajoor-service, and .github/workflows/deploy-services.yml installs all
# of them into the GitHub environment's K8S_NAMESPACE. var.namespace must equal that value;
# the IRSA roles (modules/iam) trust service accounts in the same namespace.
#
# This replaces six per-domain namespaces (auth-customer-provider, booking-dispatch-location,
# ...) that matched neither the deployables nor the pipeline.
##############################################################

resource "kubernetes_namespace" "this" {
  metadata {
    name = var.namespace

    labels = merge(var.labels, {
      "app.kubernetes.io/managed-by" = "terraform"
      "app.kubernetes.io/part-of"    = "jihajoor"
    })

    annotations = {
      "jihajoor.io/description" = "Ji-Hajoor deployables: api-gateway, identity-app, jobs-app, money-app, engagement-app"
    }
  }
}

# ─────────────────────────────────────────────
# Resource Quota
# Sized for every deployable at its autoscaling maximum; see var.quota.
# ─────────────────────────────────────────────
resource "kubernetes_resource_quota" "this" {
  metadata {
    name      = "default-quota"
    namespace = kubernetes_namespace.this.metadata[0].name
  }

  spec {
    hard = var.quota
  }
}

# ─────────────────────────────────────────────
# LimitRange
# Default requests/limits for containers that don't specify their own (the chart always does).
# ─────────────────────────────────────────────
resource "kubernetes_limit_range" "this" {
  metadata {
    name      = "default-limits"
    namespace = kubernetes_namespace.this.metadata[0].name
  }

  spec {
    limit {
      type = "Container"
      default = {
        cpu    = "500m"
        memory = "512Mi"
      }
      default_request = {
        cpu    = "100m"
        memory = "128Mi"
      }
      max = {
        cpu    = "4"
        memory = "8Gi"
      }
    }

    limit {
      type = "Pod"
      max = {
        cpu    = "8"
        memory = "16Gi"
      }
    }
  }
}

# ─────────────────────────────────────────────
# Network Policies
# Default deny-all ingress, then: the deployables may call each other (they share the
# namespace), and the ingress controller may reach the API gateway only.
# ─────────────────────────────────────────────
resource "kubernetes_network_policy" "deny_all_ingress" {
  metadata {
    name      = "deny-all-ingress"
    namespace = kubernetes_namespace.this.metadata[0].name
  }

  spec {
    pod_selector {} # Selects all pods in namespace

    policy_types = ["Ingress"]
    # No ingress rules = deny all ingress by default
  }
}

resource "kubernetes_network_policy" "allow_same_namespace" {
  metadata {
    name      = "allow-same-namespace"
    namespace = kubernetes_namespace.this.metadata[0].name
  }

  spec {
    pod_selector {}

    policy_types = ["Ingress"]

    ingress {
      from {
        pod_selector {}
      }
    }
  }
}

resource "kubernetes_network_policy" "allow_ingress_to_api_gateway" {
  metadata {
    name      = "allow-ingress-to-api-gateway"
    namespace = kubernetes_namespace.this.metadata[0].name
  }

  spec {
    # The chart's selector label for the api-gateway release.
    pod_selector {
      match_labels = {
        "app.kubernetes.io/name" = "api-gateway"
      }
    }

    policy_types = ["Ingress"]

    ingress {
      from {
        namespace_selector {
          match_labels = {
            "jihajoor.io/tier" = "ingress"
          }
        }
      }
      ports {
        port     = "8080"
        protocol = "TCP"
      }
    }
  }
}

# Prometheus (namespace "monitoring") scrapes every hosted service's /prometheus endpoint
# (infra/k8s/observability/prometheus-values.yaml).
resource "kubernetes_network_policy" "allow_monitoring" {
  metadata {
    name      = "allow-monitoring"
    namespace = kubernetes_namespace.this.metadata[0].name
  }

  spec {
    pod_selector {
      match_labels = {
        "app.kubernetes.io/part-of" = "jihajoor"
      }
    }

    policy_types = ["Ingress"]

    ingress {
      from {
        namespace_selector {
          match_labels = {
            "kubernetes.io/metadata.name" = var.monitoring_namespace
          }
        }
      }
    }
  }
}
