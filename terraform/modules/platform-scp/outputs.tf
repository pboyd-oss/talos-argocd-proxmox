output "policy_id" {
  description = "ID of the SCP — useful for auditing which accounts it is attached to"
  value       = aws_organizations_policy.platform_deploy_protection.id
}

output "policy_arn" {
  description = "ARN of the SCP"
  value       = aws_organizations_policy.platform_deploy_protection.arn
}
