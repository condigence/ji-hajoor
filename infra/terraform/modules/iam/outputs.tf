output "irsa_role_arns" {
  description = "Map of deployable (= its ServiceAccount name) to IAM role ARN; set it as serviceAccount.annotations.eks.amazonaws.com/role-arn for that release"
  value       = { for k, v in aws_iam_role.service : k => v.arn }
}

output "irsa_role_names" {
  description = "Map of deployable to IAM role name"
  value       = { for k, v in aws_iam_role.service : k => v.name }
}
