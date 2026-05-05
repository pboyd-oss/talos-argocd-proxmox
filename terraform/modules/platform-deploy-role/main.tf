locals {
  role_name     = "platform-deploy-${var.team_slug}-${var.environment}"
  irsa_role_arn = "arn:aws:iam::${var.platform_account_id}:role/${var.platform_irsa_role_name}"
}

# Trust policy encoding the full verification chain from the Token Service.
#
# The Token Service only calls AssumeRole after verifying:
#   1. Jenkins OIDC JWT (sub = platform/{team}/release, build in-progress)
#   2. Kubernetes projected SA token (request came from platform-deployer pod)
#   3. cosign scan/v1 attestation on the image (trivy + checkov passed)
#   4. image belongs to this team (harbor.tuxgrid.com/{team}/...)
#   5. role_arn is in the platform-controlled authorized-roles ConfigMap
#
# The conditions below enforce that those checks ran and produced the right context.
# The team account role does not need to trust the Token Service blindly — it
# requires evidence of the full pipeline in the form of specific tags and source identity.
data "aws_iam_policy_document" "trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole", "sts:TagSession", "sts:SetSourceIdentity"]

    principals {
      type        = "AWS"
      identifiers = [local.irsa_role_arn]
    }

    # Session must be named exactly as the Token Service names it.
    condition {
      test     = "StringEquals"
      variable = "sts:RoleSessionName"
      values   = ["platform-release"]
    }

    # Token Service tags the session with the verified team slug — must match this role.
    # Prevents the Token Service from accidentally or maliciously using team-a's
    # credentials to assume team-b's role.
    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/team"
      values   = [var.team_slug]
    }

    # Token Service tags the session with the verified environment — must match this role.
    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/environment"
      values   = [var.environment]
    }

    # SourceIdentity encodes the exact Jenkins pipeline and build number.
    # It is immutable through role chaining and appears in every CloudTrail event,
    # giving a complete audit trail from AWS API call back to the Jenkins build.
    condition {
      test     = "StringLike"
      variable = "sts:SourceIdentity"
      values   = ["platform/${var.team_slug}/release/*"]
    }
  }
}

# Permission boundary: the hard cap on what this role can ever do, regardless of
# what policies the team attaches to it. Set at role creation by the platform team.
#
# Effective permissions = (attached policies) ∩ (this boundary) ∩ (session policy).
# All three must allow an action. Any Deny wins. Even a mistake in the session policy
# cannot grant more than the boundary allows.
#
# The boundary is self-sealing: it denies iam:PutRolePermissionsBoundary and
# iam:DeleteRolePermissionsBoundary, so the deploy role cannot remove or replace it.
# checkov:skip=CKV_AWS_355:Boundary uses a deny-list pattern — Allow * is intentional and the explicit Deny statements below it remove the dangerous action categories. Scoping is enforced at the session policy level.
# checkov:skip=CKV_AWS_356:Same reasoning as CKV_AWS_355 — resource-level restriction is the session policy's job, not the boundary's.
# checkov:skip=CKV2_AWS_40:Boundary is attached to a role, not a user — the check does not apply here.
data "aws_iam_policy_document" "boundary" {
  # Allow all normal service operations. The denies below carve out the exceptions.
  statement {
    sid       = "AllowServiceOps"
    effect    = "Allow"
    actions   = ["*"]
    resources = ["*"]
  }

  # Block privilege escalation: cannot create, modify, or delete IAM identities
  # or their policies. iam:CreateServiceLinkedRole is intentionally NOT blocked —
  # many services (ECS, RDS, etc.) require creating their own service-linked roles.
  statement {
    sid    = "DenyIAMEscalation"
    effect = "Deny"
    actions = [
      "iam:AddUserToGroup",
      "iam:AttachGroupPolicy",
      "iam:AttachRolePolicy",
      "iam:AttachUserPolicy",
      "iam:CreateAccessKey",
      "iam:CreateGroup",
      "iam:CreateLoginProfile",
      "iam:CreateOpenIDConnectProvider",
      "iam:CreatePolicy",
      "iam:CreatePolicyVersion",
      "iam:CreateRole",
      "iam:CreateSAMLProvider",
      "iam:CreateUser",
      "iam:DeleteGroupPolicy",
      "iam:DeletePolicy",
      "iam:DeletePolicyVersion",
      "iam:DeleteRole",
      "iam:DeleteRolePermissionsBoundary",
      "iam:DeleteRolePolicy",
      "iam:DeleteUser",
      "iam:DeleteUserPolicy",
      "iam:DetachGroupPolicy",
      "iam:DetachRolePolicy",
      "iam:DetachUserPolicy",
      "iam:PutGroupPolicy",
      "iam:PutRolePermissionsBoundary",
      "iam:PutRolePolicy",
      "iam:PutUserPermissionsBoundary",
      "iam:PutUserPolicy",
      "iam:RemoveUserFromGroup",
      "iam:SetDefaultPolicyVersion",
      "iam:UpdateAssumeRolePolicy",
      "iam:UpdateGroup",
      "iam:UpdateRole",
      "iam:UpdateRoleDescription",
      "iam:UpdateUser",
    ]
    resources = ["*"]
  }

  # Block STS role chaining: cannot assume another role to escalate privileges.
  # Terraform deployments operate with the credentials issued directly by the
  # Token Service — they do not need to chain to a second role.
  statement {
    sid    = "DenySTSChaining"
    effect = "Deny"
    actions = [
      "sts:AssumeRole",
      "sts:AssumeRoleWithSAML",
      "sts:AssumeRoleWithWebIdentity",
    ]
    resources = ["*"]
  }

  # Block billing, cost management, and AWS Organizations operations.
  statement {
    sid    = "DenyBillingAndOrgs"
    effect = "Deny"
    actions = [
      "account:*",
      "aws-portal:*",
      "billing:*",
      "budgets:*",
      "ce:*",
      "consolidatedbilling:*",
      "cur:*",
      "freetier:*",
      "invoicing:*",
      "organizations:*",
      "payments:*",
      "purchase-orders:*",
      "savingsplans:*",
      "tax:*",
    ]
    resources = ["*"]
  }
}

# checkov:skip=CKV_AWS_355:Boundary uses a deny-list pattern — see data.aws_iam_policy_document.boundary above.
# checkov:skip=CKV_AWS_356:Same reasoning — resource scoping is enforced by the session policy.
resource "aws_iam_policy" "boundary" {
  name        = "platform-deploy-boundary-${var.team_slug}-${var.environment}"
  description = "Permission boundary for platform deploy role — managed by platform team, do not modify"
  policy      = data.aws_iam_policy_document.boundary.json

  tags = {
    managed-by  = "platform"
    team        = var.team_slug
    environment = var.environment
  }
}

resource "aws_iam_role" "deploy" {
  name                 = local.role_name
  assume_role_policy   = data.aws_iam_policy_document.trust.json
  permissions_boundary = aws_iam_policy.boundary.arn

  tags = {
    managed-by  = "platform"
    team        = var.team_slug
    environment = var.environment
  }
}

resource "aws_iam_role_policy_attachment" "deploy" {
  for_each   = toset(var.policy_arns)
  role       = aws_iam_role.deploy.name
  policy_arn = each.value
}
