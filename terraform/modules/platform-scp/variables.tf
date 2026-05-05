variable "target_ids" {
  description = "OU IDs or account IDs to attach the SCP to (e.g. [\"ou-xxxx-yyyyyyyy\"] for the team accounts OU)"
  type        = list(string)
}

variable "platform_management_role_arn" {
  description = <<-EOT
    ARN of the IAM role that the platform team uses to manage team accounts.
    This role is excluded from all SCP denies — it retains the ability to update
    the deploy role module when the platform team needs to.
    Example: arn:aws:iam::111122223333:role/platform-management
  EOT
  type        = string
}
