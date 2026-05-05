variable "team_slug" {
  description = "Team identifier matching the platform pipeline slug, e.g. team-a"
  type        = string
}

variable "environment" {
  description = "Deployment environment matching the platform environment name, e.g. production-us"
  type        = string
}

variable "platform_account_id" {
  description = "AWS account ID of the platform account running the Token Service"
  type        = string
}

variable "platform_irsa_role_name" {
  description = "Name of the IRSA role in the platform account"
  type        = string
  default     = "platform-token-service-irsa"
}

variable "policy_arns" {
  description = "IAM policy ARNs to attach to the deploy role (what Terraform actually needs to do)"
  type        = list(string)
  default     = []
}
