# Vault setup for oms-gateway

Companion to `oms-main/vault/README.md` — read that first for the general
shape (KV v2 at `secret/`, Kubernetes auth, one-time per-environment
setup). This file only covers what's specific to the gateway.

## What the app reads

Just one value, whenever `spring.cloud.vault.enabled=true`:

- `REDIS_PASSWORD`

Same path as oms-main: `secret/oms/<profile>` (Spring Cloud Vault's default
context resolution again — `spring.application.name` + active profile, see
`pom.xml`'s comment on `spring.cloud.vault.kv.default-context=oms`, which
deliberately overrides the app name so both apps land on the identical
path). This is a single shared secret, not a duplicate: oms-main already
writes `REDIS_PASSWORD` there for its own use, and the gateway just reads
the same key. Nothing new to add to `vault kv put secret/oms/prod ...` —
if you've already run oms-main's setup, the value the gateway needs is
already there.

## Local dev

Nothing to do — same `vault` dev-mode container oms-main's docker-compose
stack starts, same `secret/oms/dev` path oms-main's `vault-init` seeds.
The gateway's `application-dev.properties` points at it with a plain dev
token, not Kubernetes auth (see that file's Vault block).

## uat / prod (Kubernetes auth)

A **separate role** from oms-main's `oms-app-{uat,prod}`, bound to the
gateway's own ServiceAccount (`k8s/12-serviceaccount.yaml`), even though it
resolves the exact same policy/path oms-main's role already uses. Keeping
the roles distinct means the gateway's ServiceAccount can be revoked or
re-scoped independently of oms-main's without touching the other — no
reason for one workload's identity compromise to imply access under the
other's role too, even though today they'd both only ever read the same
KV path.

One-time setup per environment (repeat for `uat` and `prod`):

```bash
# Steps 1–3 (enable the auth method, point it at the cluster, and the
# policy itself) are identical to oms-main's — if you've already run those
# for oms-main, skip straight to step 4. Re-running steps 1–2 is harmless
# (idempotent); step 3 (oms-prod-policy) already grants read on
# secret/data/oms/prod, which is all the gateway needs too, so there's no
# new policy to write.

# 4. Bind that same policy to a NEW role tied to the gateway's own
#    ServiceAccount + namespace.
vault write auth/kubernetes/role/oms-gateway-prod \
  bound_service_account_names=oms-gateway \
  bound_service_account_namespaces=oms \
  policies=oms-prod-policy \
  ttl=1h
```

No new `vault kv put` step — the secret data itself already exists from
oms-main's setup (or the shared step 5 in its README, if you're doing both
for the first time together).

The role name (`oms-gateway-prod` / `oms-gateway-uat`) matches
`VAULT_KUBERNETES_ROLE` in `application-prod.properties` — change one,
change the other.

## Rotating a secret

Same mechanism and same caveat as oms-main's README: `vault kv put` writes
a new value, but Spring Cloud Vault only reads at startup here (no
`spring.cloud.vault.config.lifecycle.enabled=true`), so a rotation needs a
restart of **both** oms-main's and the gateway's pods to actually take
effect everywhere that reads it.
