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

## What this deliberately does NOT do yet

- No aggregation/composition — that's the `oms-bff` module in step 2
- No GraphQL
- `oms-main`'s own auth/rate-limiting stays fully in place — this is
  additive defense in depth for now, not a replacement. Retiring the
  monolith-side checks is a later, separate decision once the gateway path
  is proven in production.

## Running locally alongside the existing docker-compose stack

Add the service block below to the root `docker-compose.yml` (not copied in
automatically — review it against your actual `.env`/network setup first).
See `docker-compose.snippet.yml` in this folder.

Then point Angular's API base URL at `http://localhost:8090` instead of
`:8080`, and update `CORS_ALLOWED_ORIGINS` — the monolith's own copy of that
env var no longer matters for browser traffic once Angular is repointed
here, but leave it set (harmless) until you're ready to remove it.

## Known gaps / things to verify before this goes further

- **Not yet built/compiled in this environment** — no network access here
  to resolve Maven dependencies, so treat this as a scaffold to build and
  run locally (`./mvnw clean package` after adding the wrapper, or plain
  `mvn`), not as verified-working code yet.
- **Token blacklist (logout)**: `oms-main`'s `TokenBlacklistService` checks
  Redis for revoked tokens on every request — the gateway's JWT validation
  only checks signature/expiry, not revocation. A logged-out-but-not-yet-
  expired token would currently be accepted at the gateway and only
  rejected once it reaches the monolith. Harmless today (the monolith still
  rejects it), but worth knowing: if you ever want the gateway to reject
  revoked tokens too, it needs the same Redis blacklist check.
- **No mvnw wrapper** — added a note in the Dockerfile; run
  `mvn -N wrapper:wrapper` here to match `oms-main`'s pinned-Maven-version
  approach before treating this as production-ready.
