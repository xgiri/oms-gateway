# oms-gateway

Step 1 of the API gateway rollout: Spring Cloud Gateway sitting in front of
the `oms-main` monolith, doing pure passthrough routing plus JWT validation
and login rate limiting at the edge. No behavior change for API consumers —
Angular just talks to this instead of the monolith directly.

## What this does right now

- Routes every `/api/v1/**` call through to `oms-main` unchanged
- Validates JWTs against `oms-main`'s own JWKS (`/.well-known/jwks.json`) —
  no shared secret, no separate key management
- Redis-backed per-IP rate limiting on `POST /api/v1/auth/login`
  (`oms-main`'s own Bucket4j login limiter stays in place too, on purpose —
  see `RateLimiterConfig`'s Javadoc)
- CORS is now handled here, since this is the origin the browser talks to
- Forwards `X-Auth-User` / `X-Auth-Roles` downstream on every request,
  populated from the validated JWT (see `AuthHeaderForwardingFilter`). Built
  for `oms-bff` to trust instead of re-verifying the JWT itself — dormant
  until `oms-bff` actually has a route here (see below)

## What this deliberately does NOT do yet

- No aggregation/composition — that's the `oms-bff` module in step 2
- No GraphQL
- **No route to `oms-bff` yet.** `AuthHeaderForwardingFilter` forwards the
  trusted headers on every request already, but until a route exists here
  pointing at `oms-bff`, nothing actually reaches it through the gateway.
  That's the next piece of wiring, not part of this increment.
- `oms-main`'s own auth/rate-limiting stays fully in place — this is
  additive defense in depth for now, not a replacement. Retiring the
  monolith-side checks is a later, separate decision once the gateway path
  is proven in production.

## Vault

Wired the same way as `oms-main`: off by default (`VAULT_ENABLED`), TOKEN
auth in dev against the same dev-mode Vault container, deliberately
overridden to read from **`secret/oms/<profile>`** — `oms-main`'s own KV
path — instead of the default `secret/oms-gateway/<profile>` a plain
`spring.application.name`-based context would give. The only secret the
gateway needs today (`REDIS_PASSWORD`) is the exact same value `oms-main`
already stores there, so both apps share one KV entry rather than
duplicating it. See the comment on `spring.cloud.vault.kv.default-context`
in `application.properties` if this ever needs to change.

Running from IntelliJ (Vault container from `oms-main`'s compose stack
already up, port 8200 published to host):

```
VAULT_ENABLED=true
VAULT_ADDR=http://localhost:8200
VAULT_TOKEN=oms-dev-root-token
```

Add these alongside `OMS_MONOLITH_URI` / `OMS_JWKS_URI` / `REDIS_HOST` in
the run configuration's environment variables. With `VAULT_ENABLED=true`,
you no longer need to set `REDIS_PASSWORD` yourself — Vault supplies it.
(`VAULT_TOKEN` here is the same throwaway dev root token `oms-main` already
uses — never a real secret, see `vault/dev/seed.sh` in `oms-main` for
where it's seeded from.)

If you'd rather not stand up Vault while iterating locally, leave
`VAULT_ENABLED` unset (defaults to `false`) and set `REDIS_PASSWORD`
directly instead, same as before — both paths work.

## Running locally alongside the existing docker-compose stack

Add the service block below to the root `docker-compose.yml` (not copied in
automatically — review it against your actual `.env`/network setup first).
See `docker-compose.snippet.yml` in this folder.

Then point Angular's API base URL at `http://localhost:8090` instead of
`:8080`, and update `CORS_ALLOWED_ORIGINS` — the monolith's own copy of that
env var no longer matters for browser traffic once Angular is repointed
here, but leave it set (harmless) until you're ready to remove it.

## Known gaps / things to verify before this goes further

- **Routing, JWT validation config, and Redis rate limiting have been run
  and confirmed working locally** (via IntelliJ against `oms-main`'s
  docker-compose stack). Vault wiring is new and not yet run — confirm
  `VAULT_ENABLED=true` actually resolves `REDIS_PASSWORD` from
  `secret/oms/dev` before relying on it; if the import fails silently
  (`optional:vault://`), the app still starts but `REDIS_PASSWORD` falls
  back to empty, which will reproduce the NOAUTH error from earlier.
- **Token blacklist (logout)**: `oms-main`'s `TokenBlacklistService` checks
  Redis for revoked tokens on every request — the gateway's JWT validation
  only checks signature/expiry, not revocation. A logged-out-but-not-yet-
  expired token would currently be accepted at the gateway and only
  rejected once it reaches the monolith. Harmless today (the monolith still
  rejects it), but worth knowing.
- **No mvnw wrapper** — added a note in the Dockerfile; run
  `mvn -N wrapper:wrapper` here to match `oms-main`'s pinned-Maven-version
  approach before treating this as production-ready.
- **Gateway actuator endpoint** (`/actuator/gateway/**`) is off by default
  in the committed config, on purpose — see CVE-2025-41243/41253. Only
  re-enable it temporarily for local debugging, never commit it enabled.
