# CareerTrack API Gateway

Spring Cloud Gateway (WebFlux) in front of the CareerTrack Auth Service. It forwards `/api/auth/**` unchanged, applies per-client rate limits, and adds a correlation ID. Identity, token checks and roles stay in Auth Service.

- Java 21, Spring Boot 4.0.7, Spring Cloud 2025.1.3 (Gateway 5.0.3), Maven
- No database. No JWT verification at the gateway yet.
- Production path: Browser → Next.js BFF (Vercel) → Caddy (EC2, TLS) → Gateway `:8080` → Auth `:8081`

## Routes

| Route | Path | Rate limit (per client, per minute) |
|---|---|---|
| `auth-login` | `POST /api/auth/login` | `AUTH_LOGIN_RATE_CAPACITY`, default 10 |
| `auth-register` | `POST /api/auth/register` | `AUTH_REGISTER_RATE_CAPACITY`, default 5 |
| `auth-refresh` | `POST /api/auth/refresh` | `AUTH_REFRESH_RATE_CAPACITY`, default 30 |
| `auth-service` | `/api/auth/**` (everything else) | none |

Over the limit the gateway returns `429`. Other namespaces are denied by default; `/actuator/health` and `/actuator/info` are open for probes.

## Upstream errors

When Auth Service cannot be used, the gateway answers itself with a small JSON body (`{"code":"503","message":"..."}`, `Cache-Control: no-store`, plus `X-Correlation-ID`):

| Situation | Status |
|---|---|
| Cannot connect (refused, DNS failure, connect timeout) | `503` + `Retry-After: 5` |
| Connected, no response within the response timeout (10 s) | `504` |
| Connection dropped or invalid response | `502` |

Responses that Auth Service returns itself, including its own `500`/`503`, are passed through unchanged. Internal host names and ports are never included in the body. See `UpstreamErrorHandler`.

## How a "client" is identified

Rate-limit buckets are keyed by **path + client address** (`ClientAddressFilter`, `RateLimitConfig`):

1. If the request carries `X-CareerTrack-BFF-Key` equal to `GATEWAY_BFF_KEY`, the IP in `X-CareerTrack-Client-IP` is used. The Next.js BFF sends both, because every browser request reaches the gateway from Vercel's shared IPs.
2. Otherwise the last `X-Forwarded-For` hop (written by Caddy) is used, or the socket address when there is none. Earlier hops are client-controlled and ignored.
3. Only IP literals are accepted (no DNS lookups). IPv6 is grouped per `/64`.

Both headers are removed before the request is forwarded, so the key never reaches Auth Service and a forged client IP is never passed upstream.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `GATEWAY_PORT` | `8080` | Listen port |
| `AUTH_SERVICE_URL` | `http://127.0.0.1:8081` | Upstream (Docker: `http://auth:8081`) |
| `GATEWAY_BFF_KEY` | empty (disabled) | Shared secret with the BFF; at least 32 characters or startup fails |
| `AUTH_*_RATE_CAPACITY` / `AUTH_*_REFILL_TOKENS` | see Routes | Per-client limits |
| `GATEWAY_RATE_LIMIT_MAX_BUCKETS` | `50000` | In-memory buckets kept; an evicted bucket starts full again |

See `.env.example`. Generate the key with `openssl rand -base64 48` and set the **same value** as `GATEWAY_BFF_KEY` in the gateway's environment on EC2 and in Vercel (server-only, never `NEXT_PUBLIC_*`).

Buckets live in memory, so limits reset when the container restarts and are not shared between replicas (the deployment runs a single instance).

## Run locally

Auth Service must be running on `8081` (or set `AUTH_SERVICE_URL`).

```bash
export AUTH_SERVICE_URL=http://127.0.0.1:8081
./mvnw spring-boot:run
```

Smoke check:

```bash
curl -i http://localhost:8080/actuator/health   # 200
curl -i http://localhost:8080/api/auth/me       # 401 from Auth Service, not from the gateway
```

## Tests

```bash
./mvnw test
```

Covers routing and status/header pass-through, upstream failures (503/504/502 and pass-through of upstream errors), rate limiting per client (separate buckets, forged headers, IPv6 `/64`, `X-Forwarded-For` trust, header stripping) and IP parsing.

## CI/CD

`.github/workflows/gateway-image.yml`: tests on pull requests; on push to `main` (or **Run workflow** on `main`) it also publishes a `linux/amd64` image to GHCR tagged with the commit SHA and `production`. The EC2 host polls the `production` tag with a systemd timer and rolls back if health checks fail.

## Not done yet

- JWT validation at the gateway (needs the cross-service identity contract first)
- Routes for the planned Application Service
