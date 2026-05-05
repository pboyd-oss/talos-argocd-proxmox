variable "eks_oidc_provider_arn" {
  description = "ARN of the EKS cluster OIDC provider, e.g. arn:aws:iam::ACCOUNT:oidc-provider/oidc.eks.REGION.amazonaws.com/id/OIDC_ID"
  type        = string
}

variable "eks_oidc_provider_url" {
  description = "URL of the EKS cluster OIDC provider without https://, e.g. oidc.eks.eu-west-1.amazonaws.com/id/OIDC_ID"
  type        = string
}
