# Example: team-a onboarding a production-us deploy role.
#
# Run this in the team's AWS account. The role_arn output must then be
# submitted to the platform team for addition to authorized-roles-configmap.yaml.
#
# After the ConfigMap is updated and ArgoCD syncs it, the Token Service will
# issue credentials for this role when platform/team-a/release runs.

terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
}

provider "aws" {
  region = "eu-west-1"
  # assumes running with credentials for the team account
}

module "platform_deploy_role" {
  source = "../"

  team_slug           = "team-a"
  environment         = "production-us"
  platform_account_id = "111122223333"  # platform team provides this

  # Policies granting whatever Terraform actually needs to do in this account.
  # The platform deploy role has no permissions by default — teams attach only
  # what their Terraform workloads require.
  policy_arns = [
    aws_iam_policy.team_a_deploy.arn,
  ]
}

resource "aws_iam_policy" "team_a_deploy" {
  name = "team-a-deploy-production-us"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject"]
        Resource = "arn:aws:s3:::team-a-production-us-*"
      },
      {
        Effect   = "Allow"
        Action   = ["lambda:UpdateFunctionCode"]
        Resource = "arn:aws:lambda:eu-west-1:*:function:team-a-*"
      },
    ]
  })
}

output "role_arn" {
  value       = module.platform_deploy_role.role_arn
  description = "Submit this ARN to the platform team for authorized-roles-configmap.yaml"
}
