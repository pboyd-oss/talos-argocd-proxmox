# Platform SCP — run this from the AWS Organizations management account.
#
# Protects platform-managed IAM resources in team member accounts against
# tampering, even by principals with AdministratorAccess in those accounts.
#
# What it guards:
#   platform-deploy-*             — the deploy roles created by platform-deploy-role module
#   platform-deploy-boundary-*   — the permission boundary policies baked into those roles
#
# The denies have no effect on the platform management role (var.platform_management_role_arn),
# which retains full access to update the module when the platform team needs to.
# SCPs never apply to the management account itself.

locals {
  # Normalise the management role ARN into a pattern that matches both
  # direct calls and cross-account assumed-role sessions.
  mgmt_role_pattern = replace(var.platform_management_role_arn, "role/", "role/*")
}

data "aws_iam_policy_document" "platform_deploy_protection" {
  # ── 1. Boundary integrity ────────────────────────────────────────────────
  # Block removing or replacing the permission boundary on any platform deploy
  # role. Without this, an account admin could strip the boundary and then
  # attach AdministratorAccess, bypassing everything the boundary enforces.
  statement {
    sid    = "ProtectDeployRoleBoundary"
    effect = "Deny"
    actions = [
      "iam:DeleteRolePermissionsBoundary",
      "iam:PutRolePermissionsBoundary",
    ]
    resources = ["arn:aws:iam::*:role/platform-deploy-*"]

    condition {
      test     = "ArnNotLike"
      variable = "aws:PrincipalArn"
      values   = [var.platform_management_role_arn]
    }
  }

  # ── 2. Boundary policy immutability ─────────────────────────────────────
  # Block modifying or deleting the boundary policy documents themselves.
  # Covers creating a new version that weakens the boundary, swapping the
  # default version to an older/weaker one, or deleting the policy entirely.
  statement {
    sid    = "ProtectBoundaryPolicy"
    effect = "Deny"
    actions = [
      "iam:CreatePolicyVersion",
      "iam:DeletePolicy",
      "iam:DeletePolicyVersion",
      "iam:SetDefaultPolicyVersion",
    ]
    resources = ["arn:aws:iam::*:policy/platform-deploy-boundary-*"]

    condition {
      test     = "ArnNotLike"
      variable = "aws:PrincipalArn"
      values   = [var.platform_management_role_arn]
    }
  }

  # ── 3. Trust policy and role lifecycle ───────────────────────────────────
  # Block updating the trust policy (which controls who can assume the role)
  # or deleting the role entirely. A tampered trust policy could allow arbitrary
  # principals to assume the role without going through the Token Service.
  statement {
    sid    = "ProtectDeployRoleTrust"
    effect = "Deny"
    actions = [
      "iam:UpdateAssumeRolePolicy",
      "iam:DeleteRole",
    ]
    resources = ["arn:aws:iam::*:role/platform-deploy-*"]

    condition {
      test     = "ArnNotLike"
      variable = "aws:PrincipalArn"
      values   = [var.platform_management_role_arn]
    }
  }

  # ── 4. Policy attachment control ─────────────────────────────────────────
  # Block attaching additional policies directly to the deploy role without
  # going through the platform module. The team declares their policies in
  # var.policy_arns and they are applied by Terraform — ad-hoc attachments
  # in the console or via ad-hoc CLI calls are blocked here.
  #
  # Note: this does NOT prevent the team from creating their own IAM policies
  # and attaching them via the platform module in their PR. The restriction is
  # on direct, out-of-band attachments that bypass the GitOps workflow.
  statement {
    sid    = "ProtectDeployRolePolicyAttachments"
    effect = "Deny"
    actions = [
      "iam:AttachRolePolicy",
      "iam:DetachRolePolicy",
      "iam:PutRolePolicy",
      "iam:DeleteRolePolicy",
    ]
    resources = ["arn:aws:iam::*:role/platform-deploy-*"]

    condition {
      test     = "ArnNotLike"
      variable = "aws:PrincipalArn"
      values   = [var.platform_management_role_arn]
    }
  }
}

resource "aws_organizations_policy" "platform_deploy_protection" {
  name        = "platform-deploy-role-protection"
  description = "Protects platform-managed deploy roles and their permission boundaries from tampering by account principals"
  type        = "SERVICE_CONTROL_POLICY"
  content     = data.aws_iam_policy_document.platform_deploy_protection.json

  tags = {
    managed-by = "platform"
  }
}

# Attach to each target OU or account (typically the OU containing all team accounts).
resource "aws_organizations_policy_attachment" "platform_deploy_protection" {
  for_each  = toset(var.target_ids)
  policy_id = aws_organizations_policy.platform_deploy_protection.id
  target_id = each.value
}
