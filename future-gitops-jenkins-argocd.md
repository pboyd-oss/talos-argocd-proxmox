# Synchronous ArgoCD Pipeline with Jenkins Trigger

## Context

The goal is a GitOps pipeline where Jenkins builds, signs, and attests a container image, optionally triggers an ArgoCD sync, and **blocks until the deployment is healthy** when it does. If Kyverno rejects the pods (missing attestations), ArgoCD goes Degraded, the wait times out, and Jenkins marks the build failed. The ArgoCD sync+wait is **opt-in per service** — teams that don't configure it get the existing fire-and-forget behaviour unchanged.

The existing infrastructure already covers: Harbor registry, cosign signing, all three attestation types (`scan/v1`, `pipeline/v1`, `spdx.dev/Document`), and Kyverno enforcement on `team-*` namespaces. The gaps are: (a) per-service Helm chart layout, (b) Jenkins pushing the updated image tag to the GitOps repo, (c) opt-in Jenkins→ArgoCD trigger+wait.

---

## Pipeline Flow

```
SCM commit
  → team/<service>/build
      ├─ Test (JUnit + JaCoCo)
      ├─ Build (skaffold → Harbor, archives artifacts.json)
      └─ Scan (platform/{team}/scan, propagate:true)
            └─ scan/v1 attestation created

  → platform/{team}/release  [RunListener also fires platform/{team}/attest concurrently]
      ├─ Verify Attestations (polls for all 3 types)
      ├─ Sign (cosign sign)
      ├─ [opt-in] Update GitOps Repo  ← clone, edit values.yaml image.tag, push to Gitea
      └─ [opt-in] ArgoCD Sync+Wait   ← argocd app sync + argocd app wait --health
                                         BLOCKS until pods are Healthy
                                         Kyverno rejects → Degraded → timeout → FAILURE

If ARGOCD_SYNC is false (default): pipeline ends after Sign — no git push, no ArgoCD interaction.
```

---

## 1. Per-Service Helm Chart Layout

Each service gets a directory under `my-apps/<env>/<service>/` in the GitOps repo, following the existing gitea/harbor pattern. The chart itself lives in a dedicated Gitea chart repo per team (e.g. `gitea.tuxgrid.com/team-a/charts`).

```
my-apps/<env>/<service>/
  kustomization.yaml    # helmCharts directive pointing to the team chart repo
  values.yaml           # image.tag: "sha256:..." — only line Jenkins writes
  namespace.yaml        # kind: Namespace, name: team-<service>
```

**`kustomization.yaml`** (mirrors existing gitea/harbor pattern):
```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: team-auth-api

helmCharts:
  - name: auth-api
    repo: https://gitea.tuxgrid.com/team-a/charts/
    version: 1.0.0
    releaseName: auth-api
    namespace: team-auth-api
    valuesFile: values.yaml
```

**`values.yaml`** (the only field Jenkins writes):
```yaml
image:
  repository: harbor.tuxgrid.com/team-a/auth-api
  tag: "sha256:abc123..."
```

**`namespace.yaml`**:
```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: team-auth-api
```

Namespace name `team-<service>` is already matched by the existing Kyverno `require-signed-images` ClusterPolicy — no policy changes needed. The `CreateNamespace=true` sync option in the my-apps AppSet creates the namespace if absent.

---

## 2. ArgoCD Local User for Jenkins

**File to modify:** `infrastructure/controllers/argocd/values.yaml`

Add under `configs.cm`:
```yaml
accounts.jenkins: apiKey
```

Add under `configs.rbac`:
```yaml
policy.csv: |
  p, role:jenkins-cd, applications, sync, my-apps/*, allow
  p, role:jenkins-cd, applications, get,  my-apps/*, allow
  g, jenkins, role:jenkins-cd
```

Generate the token once after deploying:
```bash
argocd account generate-token --account jenkins
```

Seal it as a SealedSecret in the `jenkins` namespace — pattern mirrors `my-apps/development/jenkins-lab/token-service-token-sealedsecret.yaml`.

---

## 3. New/Modified Files

| File | Action | Purpose |
|------|--------|---------|
| `infrastructure/controllers/argocd/values.yaml` | Modify | Add `jenkins` local account + RBAC policy |
| `my-apps/development/jenkins-lab/argocd-token-sealedsecret.yaml` | New | SealedSecret for ArgoCD API token |
| `my-apps/development/jenkins-lab/gitea-push-credentials-sealedsecret.yaml` | New | SealedSecret for Gitea push token |
| `my-apps/development/jenkins-lab/casc-configmap.yaml` | Modify | Add `platform-argocd` pod template + 2 credential entries |
| `my-apps/development/jenkins-lab/kustomization.yaml` | Modify | Add 2 new SealedSecrets to `resources:` |
| `seed-jobs/pipelines/PlatformReleasePipeline.groovy` | Modify | Add `Update GitOps Repo` + `ArgoCD Sync` stages after existing `Sign` stage |
| `seed-jobs/teams/<team>.yml` | Modify | Add `service_name: <name>` (for `TUXGRID_SERVICE_NAME`) and optional `argocd_sync: true` to opt in |
| Per-service: `my-apps/<env>/<service>/` | New (×3 per service) | `kustomization.yaml`, `values.yaml`, `namespace.yaml` |

---

## 4. Jenkins Pipeline Changes

### Opt-in control

The ArgoCD integration is enabled per service via `seed-jobs/teams/<team>.yml`:

```yaml
# Add to opt in; omit (or set false) to keep existing behaviour unchanged
argocd_sync: true
service_name: auth-api   # used as TUXGRID_SERVICE_NAME folder env var
```

`MasterSeedPipeline.groovy` already injects folder env vars from team YAML fields. Add `ARGOCD_SYNC` to that injection. Default is `'false'` when the field is absent.

**`casc-configmap.yaml`** — new pod template:
```yaml
- name: "platform-argocd"
  label: "platform-argocd"
  serviceAccount: "jenkins"
  containers:
    - name: "argocd"
      image: "harbor.tuxgrid.com/argoproj/argocd:v2.14.0"
      command: "cat"
      ttyEnabled: true
      resourceRequestCpu: "50m"
      resourceRequestMemory: "64Mi"
```

**`PlatformReleasePipeline.groovy`** — two new stages after `Sign`, gated by `ARGOCD_SYNC`:

```groovy
stage('Update GitOps Repo') {
    when { environment name: 'ARGOCD_SYNC', value: 'true' }
    steps {
        script { updateGitOpsImageTag(params.ENVIRONMENT) }
    }
}

stage('ArgoCD Sync') {
    when { environment name: 'ARGOCD_SYNC', value: 'true' }
    steps {
        script { syncAndWaitArgoCD(params.ENVIRONMENT) }
    }
}
```

Helper methods:
```groovy
private void updateGitOpsImageTag(String environment) {
    def imageTag = readJSON(file: 'artifacts.json').builds[0].tag.split('@')[1]
    def service  = env.TUXGRID_SERVICE_NAME

    withCredentials([usernamePassword(
        credentialsId: 'gitea-push-credentials',
        usernameVariable: 'GITEA_USER', passwordVariable: 'GITEA_TOKEN'
    )]) {
        container('skaffold') {
            sh """
                set -e
                git clone https://\${GITEA_USER}:\${GITEA_TOKEN}@gitea.tuxgrid.com/admin/talos-argocd-proxmox.git gitops
                cd gitops
                git config user.email "jenkins@tuxgrid.com"
                git config user.name  "Jenkins Platform"
                VALUESFILE="my-apps/${environment}/${service}/values.yaml"
                sed -i "s|^  tag:.*|  tag: \\\"${imageTag}\\\"|" "\$VALUESFILE"
                git add "\$VALUESFILE"
                git commit -m "ci(${service}): update image tag [skip ci]"
                for i in 1 2 3; do
                    git pull --rebase origin main && git push origin main && break
                    sleep 5
                done
            """
        }
    }
}

private void syncAndWaitArgoCD(String environment) {
    def appName = "my-apps-${env.TUXGRID_SERVICE_NAME}"
    def server  = "argocd-server.argocd.svc.cluster.local"

    withCredentials([string(credentialsId: 'argocd-token', variable: 'ARGOCD_AUTH_TOKEN')]) {
        container('argocd') {
            sh """
                set -e
                # Force refresh so ArgoCD re-fetches from Gitea before syncing
                argocd app get ${appName} --server ${server} --insecure \
                    --auth-token \$ARGOCD_AUTH_TOKEN --refresh

                argocd app sync ${appName} --server ${server} --insecure \
                    --auth-token \$ARGOCD_AUTH_TOKEN --prune --timeout 120

                argocd app wait ${appName} --server ${server} --insecure \
                    --auth-token \$ARGOCD_AUTH_TOKEN --health --timeout 300
            """
        }
    }
}
```

**Why `--refresh` before sync:** ArgoCD caches manifests with a ~3 min polling interval. Without a hard refresh, it may sync from cache and not see the tag Jenkins just pushed, causing a no-op sync with the old image.

**Teams that don't set `argocd_sync: true`** get `ARGOCD_SYNC=false`, both `when` conditions skip, and the pipeline ends after `Sign` exactly as today.

---

## 5. Verification

1. Push a code change to a service repo on Gitea
2. Confirm the Jenkins build job triggers (SCM webhook)
3. Confirm `platform/{team}/scan` runs and creates `scan/v1` attestation on Harbor
4. Confirm `platform/{team}/release` runs:
   - `Update GitOps Repo` stage: check `my-apps/<env>/<service>/values.yaml` in Gitea for updated tag
   - `ArgoCD Sync` stage: watch ArgoCD UI — app should go `OutOfSync → Syncing → Healthy`
5. Confirm Kyverno admitted the pod: `kubectl get events -n team-<service>` — no `blocked by policy` events
6. Break it: deploy an unsigned image manually → confirm Kyverno blocks it → ArgoCD shows Degraded → Jenkins wait times out → build marked FAILED
