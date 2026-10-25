##############################################################
# IAM Module — Ji-Hajoor
# IAM roles with IRSA (IAM Roles for Service Accounts), one per
# deployable, scoped to what the services it hosts need.
##############################################################

locals {
  # One IRSA role per deployable. The Helm chart (helm/jihajoor-service, serviceAccount.create)
  # creates a ServiceAccount named after the deployable, in the namespace the deploy pipeline
  # uses: var.namespace, which must equal the GitHub environment's K8S_NAMESPACE. A deployable
  # hosts several services in one JVM, so its role carries the union of what they need.
  deployables = {
    # Routing only; reads its secrets.
    "api-gateway" = { s3 = false, pii = false }
    # auth, customer, provider, verification: profile photos and verification documents in S3,
    # PII encryption.
    "identity-app" = { s3 = true, pii = true }
    # booking (job photos in S3), catalog, pricing, dispatch, location, chat, promotion.
    "jobs-app" = { s3 = true, pii = false }
    # payment (PII: settlement bank details), invoice (invoice PDFs in S3).
    "money-app" = { s3 = true, pii = true }
    # notification, complaint and rating-review (attachments in S3), admin, reporting, outbox.
    "engagement-app" = { s3 = true, pii = false }
  }
}

# ─────────────────────────────────────────────
# IRSA Role per Deployable
# ─────────────────────────────────────────────
resource "aws_iam_role" "service" {
  for_each = local.deployables

  name = "${var.name_prefix}-irsa-${each.key}"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = var.eks_oidc_provider_arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${var.eks_oidc_provider_url}:sub" = "system:serviceaccount:${var.namespace}:${each.key}"
          "${var.eks_oidc_provider_url}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })

  tags = merge(var.tags, {
    Name           = "${var.name_prefix}-irsa-${each.key}"
    Namespace      = var.namespace
    ServiceAccount = each.key
  })
}

# ─────────────────────────────────────────────
# Secrets Manager read policy (all services need this)
# ─────────────────────────────────────────────
resource "aws_iam_policy" "secrets_read" {
  name        = "${var.name_prefix}-secrets-read"
  description = "Allow reading Ji-Hajoor secrets from Secrets Manager"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "SecretsRead"
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue",
          "secretsmanager:DescribeSecret",
        ]
        Resource = "arn:${var.aws_partition}:secretsmanager:${var.aws_region}:${var.aws_account_id}:secret:${var.name_prefix}/*"
      },
      {
        Sid    = "KmsDecryptSecrets"
        Effect = "Allow"
        Action = [
          "kms:Decrypt",
          "kms:DescribeKey",
        ]
        Resource = var.kms_key_pii_arn
      }
    ]
  })

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "secrets_read" {
  for_each = local.deployables

  policy_arn = aws_iam_policy.secrets_read.arn
  role       = aws_iam_role.service[each.key].name
}

# ─────────────────────────────────────────────
# S3 policy (documents, photos, invoices, media)
# ─────────────────────────────────────────────
resource "aws_iam_policy" "s3_rw" {
  name        = "${var.name_prefix}-s3-rw"
  description = "Read/write access to Ji-Hajoor S3 buckets"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "S3ObjectAccess"
        Effect = "Allow"
        Action = [
          "s3:PutObject",
          "s3:GetObject",
          "s3:DeleteObject",
          "s3:GetObjectVersion",
        ]
        Resource = [for arn in var.s3_bucket_arns : "${arn}/*"]
      },
      {
        Sid    = "S3ListBuckets"
        Effect = "Allow"
        Action = ["s3:ListBucket", "s3:GetBucketLocation"]
        Resource = var.s3_bucket_arns
      },
      {
        Sid    = "KmsS3"
        Effect = "Allow"
        Action = [
          "kms:GenerateDataKey",
          "kms:Decrypt",
          "kms:DescribeKey",
        ]
        Resource = var.kms_key_s3_arn
      }
    ]
  })

  tags = var.tags
}

# Only deployables hosting a service that touches S3 get this policy
locals {
  s3_deployables = toset([for name, d in local.deployables : name if d.s3])
}

resource "aws_iam_role_policy_attachment" "s3_rw" {
  for_each = local.s3_deployables

  policy_arn = aws_iam_policy.s3_rw.arn
  role       = aws_iam_role.service[each.key].name
}

# ─────────────────────────────────────────────
# PII KMS policy (services that encrypt/decrypt PII)
# ─────────────────────────────────────────────
resource "aws_iam_policy" "kms_pii" {
  name        = "${var.name_prefix}-kms-pii"
  description = "Encrypt/decrypt PII fields using KMS"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "KmsPii"
      Effect = "Allow"
      Action = [
        "kms:Encrypt",
        "kms:Decrypt",
        "kms:ReEncrypt*",
        "kms:GenerateDataKey*",
        "kms:DescribeKey",
      ]
      Resource = var.kms_key_pii_arn
    }]
  })

  tags = var.tags
}

locals {
  pii_deployables = toset([for name, d in local.deployables : name if d.pii])
}

resource "aws_iam_role_policy_attachment" "kms_pii" {
  for_each = local.pii_deployables

  policy_arn = aws_iam_policy.kms_pii.arn
  role       = aws_iam_role.service[each.key].name
}

# ─────────────────────────────────────────────
# RDS KMS policy (all services that hit the DB)
# ─────────────────────────────────────────────
resource "aws_iam_policy" "kms_rds" {
  name        = "${var.name_prefix}-kms-rds"
  description = "Allow services to use the RDS KMS key"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "KmsRds"
      Effect = "Allow"
      Action = [
        "kms:Decrypt",
        "kms:GenerateDataKey*",
        "kms:DescribeKey",
      ]
      Resource = var.kms_key_rds_arn
    }]
  })

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "kms_rds" {
  for_each = local.deployables

  policy_arn = aws_iam_policy.kms_rds.arn
  role       = aws_iam_role.service[each.key].name
}
