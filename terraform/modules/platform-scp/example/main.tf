# Example: attaching the platform deploy role protection SCP to the team accounts OU.
#
# Run this from the AWS Organizations management account — the account that owns
# the organization root. It needs organizations:CreatePolicy and
# organizations:AttachPolicy permissions.
#
# This is a one-time platform setup, not per-team. A single SCP attached to the
# OU covering all team accounts protects every platform-deploy-* role in every
# account under that OU.
#
# The SCP blocks any principal in a team account (including account admins) from:
#   - stripping or replacing the permission boundary on platform-deploy-* roles
#   - weakening or deleting the platform-deploy-boundary-* policies
#   - rewriting the trust policy on platform-deploy-* roles
#   - attaching/detaching policies to platform-deploy-* roles out-of-band
#
# The platform management role (below) is the only carve-out — it retains full
# access so the platform team can update the module when needed.

terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
}

provider "aws" {
  region = "eu-west-1"
  # assumes running with credentials for the Organizations management account
}

# Look up the OU that contains all team accounts so we can attach the SCP to it.
# Replace the org ID and OU name to match your organization structure.
data "aws_organizations_organization" "this" {}

data "aws_organizations_organizational_units" "root" {
  parent_id = data.aws_organizations_organization.this.roots[0].id
}

locals {
  # Adjust "team-accounts" to match the name of your team accounts OU.
  team_accounts_ou = one([
    for ou in data.aws_organizations_organizational_units.root.children :
    ou if ou.name == "team-accounts"
  ])
}

module "platform_scp" {
  source = "../"

  # Attach to the OU that contains all team member accounts.
  # The SCP is inherited by every account in the OU and all child OUs.
  target_ids = [local.team_accounts_ou.id]

  # The IAM role the platform team uses when managing team accounts cross-account.
  # This is the only principal excluded from the SCP denies — everyone else in
  # the team accounts (including account admins) is blocked from touching
  # platform-deploy-* roles and their boundaries.
  platform_management_role_arn = "arn:aws:iam::${var.platform_account_id}:role/platform-management"
}

variable "platform_account_id" {
  description = "AWS account ID of the platform account (where the Token Service and management role live)"
  type        = string
}

output "scp_id" {
  value       = module.platform_scp.policy_id
  description = "SCP ID — verify attachment with: aws organizations list-targets-for-policy --policy-id <id>"
}
