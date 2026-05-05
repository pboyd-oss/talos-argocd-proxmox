output "role_arn" {
  description = "ARN to set as eks.amazonaws.com/role-arn on the platform-token-service ServiceAccount"
  value       = aws_iam_role.irsa.arn
}
