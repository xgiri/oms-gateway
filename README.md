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
  populated from the validated JWT (see `AuthHeaderForwardingFilter`) —
  `oms-bff` trusts these instead of re-verifying the JWT itself, see its
  `TrustedHeaderAuthentication`
- Routes `/graphql` and `/graphiql/**` through to `oms-bff`
  (`OMS_BFF_URI`, defaults to `http://bff:8095`) — `/graphql` goes through
  the same JWT-authenticated path as `/api/v1/**`; `/graphiql` (the dev-only
  explorer page) is left open the same way Swagger UI is for `oms-main`

## What this deliberately does NOT do yet

- No GraphQL aggregation logic of its own — that's all in `oms-bff`; this
  gateway only routes and forwards identity to it
- `oms-main`'s own auth/rate-limiting stays fully in place — this is
  additive defense in depth for now, not a replacement. Retiring the
  monolith-side checks is a later, separate decision once the gateway path
  is proven in production.
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

`oms-bff`'s own service block (see its `docker-compose.snippet.yml`) needs
merging in too, alongside this one — the gateway's route to it
(`OMS_BFF_URI`, defaults to `http://bff:8095`, the compose service name)
won't resolve anything without it.

## Known gaps / things to verify before this goes further

- **Routing, JWT validation config, and Redis rate limiting have been run
  and confirmed working locally** (via IntelliJ against `oms-main`'s
  docker-compose stack). Vault wiring is new and **still not actually run
  against a live Vault** — that part can't be verified from a diff, only by
  running it (see "Vault" section above for the exact env vars to set in
  an IntelliJ run config). What's now closed is the *silent-failure* risk
  specifically: previously, an unreachable Vault or a missing
  `REDIS_PASSWORD` key at `secret/oms/dev` would fail the
  `optional:vault://` import quietly, the app would start up looking
  healthy, and the actual cause would only surface later as a confusing
  Redis `NOAUTH` error. `VaultRedisPasswordGuard` now refuses to start at
  all when `VAULT_ENABLED=true` but the resolved Redis password is empty —
  same immediate, unambiguous failure at the root cause instead of a
  symptom three layers downstream (see `VaultRedisPasswordGuardTest` for
  the covered cases). Run it once for real before treating this as done.
- ~~**Token blacklist (logout)**~~ — closed. `JwtDecoderConfig` wraps the
  JWKS-backed decoder with a check against the same
  `blacklist:jwt:<sha256-hex>` Redis keys `oms-main`'s `TokenBlacklistService`
  writes on logout (see `config/TokenBlacklistService.java` here — read-only
  mirror, same Redis instance, same hash scheme). A revoked token is now
  rejected at the edge as a 401, same as an invalid signature, instead of
  reaching the monolith first. The decision logic is factored out into
  `BlacklistCheckingJwtDecoder` and covered by
  `BlacklistCheckingJwtDecoderTest` (allow / reject / fail-fast-without-a-
  Redis-call, all against stub collaborators — no live JWKS or Redis
  needed to run it).
- ~~**No mvnw wrapper**~~ — closed. `mvnw`/`mvnw.cmd` and
  `.mvn/wrapper/maven-wrapper.properties` are checked in now, pinned to the
  same Maven version and wrapper-plugin version as `oms-main`
  (`3.9.16` / `only-script` / wrapper `3.3.4`). The Dockerfile's build stage
  now uses `./mvnw` instead of a generic `maven:3.9-eclipse-temurin-21`
  base image, same as `oms-main`'s Dockerfile.
- ~~**Per-IP login rate limiting breaks once there's a proxy in front**~~ —
  closed. `clientIpKeyResolver` (`RateLimiterConfig`) reads
  `getRemoteAddress()`, which without help returns whatever's immediately
  in front of this app — `ingress-nginx`'s pod IP in k8s — collapsing
  every external client onto one shared bucket. `server.forward-headers-strategy=framework`
  (application.properties) makes WebFlux honor `X-Forwarded-For` and
  rewrite `getRemoteAddress()` to the real client IP before any filter
  sees the request; see that property's comment for the trust-boundary
  reasoning (ClusterIP-only Service + `ingress-nginx`'s own
  `use-forwarded-headers: false` default are what make this safe to trust).
  `RateLimiterConfigTest` covers the resolver's own extraction/fallback
  logic — the forwarded-header rewriting itself is Spring's own tested
  behavior, not re-tested here.
- **Gateway actuator endpoint** (`/actuator/gateway/**`) is off by default
  in the committed config, on purpose — see CVE-2025-41243/41253. Two
  independent layers now stop it reaching production even if that ever
  slips: (1) network-level — the metrics port it lives on is never part of
  `k8s/03-service-gateway.yaml` or `k8s/04-ingress.yaml`, so it's
  unreachable from outside the cluster regardless of what's exposed on it;
  (2) config-level — `ActuatorExposureGuard` refuses the app to even start
  under the `prod` profile if `gateway` (or `*`) ever ends up in
  `management.endpoints.web.exposure.include`, whether from a forgotten
  local override or a stray env var (see
  `ActuatorExposureGuardTest` for the covered cases). Still fine to
  re-enable temporarily for local (non-`prod`-profile) debugging — just
  never commit it enabled.
