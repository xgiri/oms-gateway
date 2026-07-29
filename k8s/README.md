# oms-gateway on Kubernetes

This is the production deployment for the gateway: the piece that took over
as the browser-facing edge from oms-main (see `../src/main/java/.../SecurityConfig.java`'s
comment — Angular talks to the gateway now, not directly to oms-main). This
directory's Ingress supersedes `oms-main/k8s/04-ingress.yaml`, which stays
in that repo commented out of its `kustomization.yaml`, not deleted — see
the note there.

## Files

| File | What it is |
|---|---|
| `00-configmap.yaml` | Non-secret env vars — routing targets, Redis, CORS, Vault connection details |
| `12-serviceaccount.yaml` | Dedicated ServiceAccount, bound to its own Vault Kubernetes-auth role |
| `02-deployment-gateway.yaml` | The gateway itself — one role, no web/worker split needed here |
| `03-service-gateway.yaml` | ClusterIP in front of the gateway pods |
| `04-ingress.yaml` | External entry point — the one from oms-main, moved here |
| `05-hpa-gateway.yaml` | Scales on CPU utilization |
| `08-pdb.yaml` | PodDisruptionBudget |
| `09-podmonitor.yaml` | Optional — Prometheus Operator scrape config (not in `kustomization.yaml` by default; see below) |
| `kustomization.yaml` | Ties it all together; `kustomize edit set image` to point at your build |

No secret manifest (no `01-secret.example.yaml` equivalent) — the gateway's
only secret, `REDIS_PASSWORD`, comes entirely from Vault at
`secret/oms/<profile>`, the same path oms-main already reads. See
`../vault/README.md`.

## Prerequisites

Same three as oms-main's `k8s/README.md` — metrics-server (HPA), an ingress
controller (this directory's `04-ingress.yaml` assumes `ingress-nginx`),
and a real Vault cluster reachable from k8s. Plus:

- **oms-main's own k8s manifests already applied**, with a `Service` named
  `oms-web` reachable in-cluster — `00-configmap.yaml`'s
  `OMS_MONOLITH_URI`/`OMS_JWKS_URI` point at it by that name.
- **A `Service` named `redis` reachable in-cluster** — the same shared
  Redis instance oms-main's `00-configmap.yaml` points at; rate limiting
  and the logout-blacklist check both need it.
- **oms-bff's own k8s manifests already applied**, with a `Service` named
  `oms-bff` reachable in-cluster — `00-configmap.yaml`'s `OMS_BFF_URI`
  points at it by that name. See `oms-bff/k8s/README.md`.

## Applying

```bash
# 1. Point the image at your real build
kustomize edit set image your-registry.example.com/oms-gateway=your-registry.example.com/oms-gateway:$GIT_SHA

# 2. Apply
kubectl apply -k .
```

Do this **after** oms-main's own manifests are applied (`OMS_MONOLITH_URI`
needs `oms-web` to already resolve) and **before** or **alongside**
removing `oms-main/k8s/04-ingress.yaml` from that module's active
resources — see the note in that repo's `kustomization.yaml`.

## What scales on what

CPU utilization via a standard HPA (`05-hpa-gateway.yaml`),
`minReplicas: 2` / `maxReplicas: 10` — same shape as oms-main's `oms-web`,
see the note at the top of `05-hpa-gateway.yaml` for the request-rate-based
alternative.

## Metrics

Same split as oms-main: a second container port, `metrics` (8091,
`management.server.port`), never part of `03-service-gateway.yaml` or
`04-ingress.yaml`, so unreachable from outside the cluster regardless of
what's exposed on it. Two ways to scrape, same either/or as oms-main's
`k8s/README.md`:

- **Prometheus Operator**: apply `09-podmonitor.yaml` (commented out of
  `kustomization.yaml` by default).
- **Plain Prometheus**: already covered by the `prometheus.io/*`
  annotations on `02-deployment-gateway.yaml`'s pod template.

## Tuning before production use

Same caveat as oms-main's `k8s/README.md` — everything commented as a
starting point is exactly that:

- `resources.requests`/`limits` on `02-deployment-gateway.yaml` (placeholder
  values, set lower than oms-main's web Deployment since there's no
  JPA/DB pool here — revisit once you have real traffic numbers)
- HPA `averageUtilization: 70`
- `maxReplicas` ceiling
