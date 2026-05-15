# Kyverno

Kyverno runs as a standalone Application (Wave 3) so its webhooks are registered
before any app PVCs are created in Wave 4+.

## Active Policies

| Policy | Type | Purpose |
|--------|------|---------|
| `longhorn-pvc-backup-audit` | Audit | Flags Longhorn PVCs with no `backup:` or `backup-exempt: "true"` label — surfaces missing backup intent via PolicyReport, never blocks admission |
| `require-signed-images` | Validate/Enforce | Requires cosign signatures on app images |
| `require-signed-platform-images` | Validate/Enforce | Requires cosign signatures on platform images |
| `restrict-platform-image-registries` | Validate/Enforce | Limits platform images to allowed registries |
| `protect-cedar-policies` | Validate/Enforce | Protects Cedar policy resources from mutation |

## Webhook Deadlock Prevention

**Incident: 2026-04-08 — Full cluster outage caused by Kyverno webhook deadlock.**

A kube-prometheus-stack auto-merge flooded Kyverno's admission controller, crashing it
with `"failed to wait for cache sync"`. With `failurePolicy: Fail` still registered,
every Deployment/StatefulSet/DaemonSet creation outside kube-system was rejected.
Longhorn couldn't restart → no storage → full deadlock. Only manual webhook deletion
broke it.

**Fix**: infrastructure namespaces are excluded from the webhook `namespaceSelector`
in `values.yaml`. Never remove these exclusions:

```
kube-system, longhorn-system, argocd, volsync-system, snapshot-controller,
cert-manager, external-secrets, 1passwordconnect, cloudnative-pg
```

**Emergency recovery** if deadlock recurs:
```bash
./scripts/emergency-webhook-cleanup.sh
```

## Canonical Policy Form

Kyverno's webhook injects `emitWarning`, `validationFailureAction`, and
`skipBackgroundRequests` defaults. Write them explicitly so ArgoCD doesn't detect
drift and show OutOfSync:

```yaml
spec:
  mutateExistingOnPolicyUpdate: false
  background: false
  emitWarning: false
  validationFailureAction: Audit
  rules:
    - name: my-rule
      skipBackgroundRequests: true
```

## Debugging

```bash
kubectl get clusterpolicy
kubectl get policyreport -A
kubectl describe policyreport -n <namespace>
kubectl logs -n kyverno -l app.kubernetes.io/component=admission-controller
```
