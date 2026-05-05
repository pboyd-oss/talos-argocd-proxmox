locals {
  sa_subject = "system:serviceaccount:platform:platform-token-service"
}

# Trust policy allowing the platform cluster's EKS OIDC provider to assume this role
# on behalf of the platform-token-service Kubernetes ServiceAccount only.
data "aws_iam_policy_document" "trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [var.eks_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.eks_oidc_provider_url}:sub"
      values   = [local.sa_subject]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.eks_oidc_provider_url}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "irsa" {
  name               = "platform-token-service-irsa"
  assume_role_policy = data.aws_iam_policy_document.trust.json

  tags = {
    managed-by = "platform"
  }
}

# Permissions: may only assume roles matching the platform naming convention,
# tag sessions, and set source identity. The wildcard on account ID is intentional —
# the authorized-roles ConfigMap in the cluster is the per-team gate, not IAM.
data "aws_iam_policy_document" "permissions" {
  statement {
    effect    = "Allow"
    actions   = ["sts:AssumeRole", "sts:TagSession", "sts:SetSourceIdentity"]
    resources = ["arn:aws:iam::*:role/platform-deploy-*"]
  }
}

resource "aws_iam_role_policy" "permissions" {
  name   = "assume-platform-deploy-roles"
  role   = aws_iam_role.irsa.name
  policy = data.aws_iam_policy_document.permissions.json
}
