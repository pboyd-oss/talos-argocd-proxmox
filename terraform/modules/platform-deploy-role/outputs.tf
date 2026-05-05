output "role_arn" {
  description = "ARN of the deploy role — add this to authorized-roles-configmap.yaml"
  value       = aws_iam_role.deploy.arn
}

output "role_name" {
  value = aws_iam_role.deploy.name
}
