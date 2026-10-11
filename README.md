# CareerTrack API Gateway — Sprint 1 Foundation

## Scope
A minimal standalone Spring Cloud Gateway Server WebFlux application. Only forwards `/api/auth/**` to the existing Auth Service **without changing the path**.

- Java 21, Spring Boot 4.0.7, Spring Cloud 2025.1.3 (Gateway 5.0.3), Maven.
- Default gateway port: `8080` (`GATEWAY_PORT`).
- Default auth-service upstream: `http://127.0.0.1:8081` (`AUTH_SERVICE_URL`).
- No JWT verification at the gateway yet. Auth Service must still enforce its own security.
- No PostgreSQL, Flyway, Redis, or domain state in the gateway.
- No implicit `/api/admin/**` or `/.well-known/jwks.json` exposure; review those routes separately.

## Run locally

Ensure Auth Service is already running on port 8081 (or override `AUTH_SERVICE_URL`).

Linux/macOS:
```bash
export GATEWAY_PORT=8080
export AUTH_SERVICE_URL=http://127.0.0.1:8081
mvn spring-boot:run
```

PowerShell:
```powershell
$env:GATEWAY_PORT = '8080'
$env:AUTH_SERVICE_URL = 'http://127.0.0.1:8081'
mvn spring-boot:run
```

## Smoke check
```bash
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/api/auth/me
```

Expected: health 200; unauthenticated `/api/auth/me` should be denied by Auth Service (normally 401) rather than by the gateway. If Auth Service is down, the gateway cannot return the normal auth response.

Run `mvn clean test` to exercise the context bootstrap test. Integration tests for actual HTTP proxying, error status preservation, authorization pass-through, and security will be added in the next iteration; **this starter has not been built or tested in this execution environment**.

## Important compatibility note
`auth-service.zip` uploaded on 2026-10-09 contains migration history through V7, while a later technical handover describes V11–V13 and additional endpoints. Reconcile with the latest checked-out Auth Service controller and tests before pinning integration contracts. The simple `/api/auth/**` route intentionally does not impose request/response DTO assumptions.

## Deferred scope
- JWT validation with Auth Service JWKS (RS256) and issuer/audience/type checks, plus a deliberate revocation strategy.
- Dedicated CMS route and strict role enforcement.
- Request correlation with header trust boundary and safe logging.
- Reactive exception handling and 502/503/504 mapping for gateway-origin errors.
- Rate limits, tested gradually (especially login/refresh).
- Next.js BFF login/refresh/cookie integration and full routing/security tests.
