# Platform Future Architecture

This document describes what the platform's CI/CD and team onboarding architecture would look like if the tooling constraint were removed. The security model — cosign attestation chain, Token Service OIDC-gated STS, Kyverno admission enforcement — carries over unchanged. The difference is that every imperative Jenkins provisioning step becomes a controller reconciliation loop.

---

## Current constraint

The platform runs on Jenkins with a Groovy job-dsl seed pattern. Team onboarding, namespace provisioning, and AWS role creation are expressed as Jenkins pipelines that shell out to `kubectl` and `terraform`. These work, but the logic is imperative, hard to test locally, and tightly coupled to Jenkins as a runtime.

---

## The 2026 model

### Single onboarding act: a `Team` custom resource

```yaml
apiVersion: platform.tuxgrid.com/v1
kind: Team
metadata:
  name: team-c
  namespace: platform
spec:
  name: "Team Charlie"
  buildCloud: build-shared
  members:
    - username: csmith
      role: admin
  repositories:
    - name: payments-api
      url: git@git.tuxgrid.com:team-c/payments-api.git
  environments:
    - name: staging
      cloud: deploy-staging
      namespace: team-c-staging
    - name: production-eu
      cloud: deploy-prod-eu
      namespace: team-c-prod
      aws:
        accountId: "111122223333"
        sessionPolicy: |
          { "Version": "2012-10-17", "Statement": [...] }
```

Creating this one resource triggers every downstream provisioning step. No PR to multiple repos, no manual checklists, no Jenkins pipelines to run.

---

### What reconciles automatically

**Crossplane** provisions AWS resources from the CR and writes ARNs back to status:

```
Team CR created
  → Crossplane Composition renders:
      aws.iam/v1beta1 Role          (platform-deploy-team-c-staging)
      aws.iam/v1beta1 Policy        (permission boundary — same deny-list as current Terraform module)
      aws.iam/v1beta1 RolePolicyAttachment
  → ARN written to Team CR status.environments[staging].roleArn
  → Token Service reads ARN from CR status (replaces authorized-roles-configmap.yaml lookup)
```

The permission boundary and SCP logic from `terraform/modules/platform-deploy-role` and `terraform/modules/platform-scp` translate directly to Crossplane Compositions — same policy, different expression.

**ArgoCD ApplicationSet** (cluster generator) renders namespace and RBAC directly onto target clusters:

```
Team CR created
  → ApplicationSet generator reads Team CRs
  → Generates one Application per environment
  → Each Application deploys to the target cluster:
      Namespace (team-c-staging)
      ServiceAccount (deploy-sa)
      Role (platform-deployer — same least-privilege rules as today)
      RoleBinding
```

No `PlatformNamespaceProvisionPipeline`. No `kubectl` in a Jenkins pod. ArgoCD syncs it like any other manifest.

**Kyverno generate** picks up the new namespace automatically (already works today — this is unchanged):

```
Namespace team-c-staging created with label tuxgrid.com/team=team-c
  → Kyverno generate installs:
      NetworkPolicy (default-deny + egress to platform services)
      LimitRange
      ResourceQuota
      (signed-image Kyverno policy already enforces on team-* namespaces)
```

**Jenkins job generation** moves to an ArgoCD Application backed by a Helm chart or Kustomize that renders JCasC YAML from Team CRs, replacing the Groovy seed pipeline entirely.

---

### CI/CD pipelines: Dagger

Build, scan, and attest pipelines are rewritten as [Dagger](https://dagger.io) modules in Go:

```go
// scan.go
func (m *Platform) Scan(ctx context.Context, image string) (*ScanResult, error) {
    trivy := dag.Container().From("aquasec/trivy:0.60.0")
    result, err := trivy.
        WithMountedFile("/image.tar", dag.Container().From(image).AsTarball()).
        WithExec([]string{"trivy", "image", "--format", "json", "--scanners", "vuln,secret", "--input", "/image.tar"}).
        Stdout(ctx)
    // ...
}
```

The same module runs identically in:
- A developer's laptop (`dagger call scan --image=...`)
- Jenkins (triggers `dagger call` — Jenkins becomes a scheduler, not a logic layer)
- GitHub Actions / Gitea Actions (same `dagger call`)

The Groovy DSL disappears. The pipeline logic is testable Go that any engineer can run locally.

---

### Internal Developer Portal

Backstage or [Port](https://www.getport.io) provides a self-service form for team onboarding:

```
Team requests onboarding via portal form
  → Portal opens a PR to platform-config repo with Team CR YAML
  → PlatformOnboardingValidate CI runs (validates CR schema, cloud access)
  → Platform engineer reviews and approves PR
  → Merge → everything above reconciles automatically
```

The platform engineer's role shifts from "run these four pipelines in order" to "review this one PR."

---

## What carries over unchanged

The security architecture is tooling-agnostic and carries over directly:

| Component | Current | Future |
|-----------|---------|--------|
| Image signing | `cosign sign` in Jenkins | `cosign sign` in Dagger module |
| Scan attestation (`scan/v1`) | `cosign attest` in Jenkins | `cosign attest` in Dagger module |
| SBOM attestation | `cosign attest --type spdxjson` | unchanged |
| Token Service | Go service, OIDC-gated STS | unchanged — reads ARN from Team CR status instead of ConfigMap |
| Kyverno admission | `require-signed-images.yaml` | unchanged |
| Permission boundary | Terraform module | Crossplane Composition (same policy) |
| SCP | Terraform module | Crossplane Composition (same policy) |
| Harbor registry | `harbor.tuxgrid.com` | unchanged |

The investment in the attestation chain and Token Service is not wasted — it's the part that travels best.

---

## Migration path

1. Add Crossplane to the platform cluster (Wave 4, same as cert-manager)
2. Write Compositions for the IAM role + boundary (translate from existing Terraform modules)
3. Add `Team` CRD and controller (or use Crossplane's composite resource claim pattern)
4. Migrate one team to the CRD model, keep Jenkins seed for others in parallel
5. Replace `authorized-roles-configmap.yaml` lookup in Token Service with CR status read
6. Migrate provisioning pipelines to Dagger modules, keep Jenkins as the trigger
7. Retire Groovy seed DSL once all teams are on CRD model
