# Step 1 - Foundation

Personal learning notes. Everything here was built and run locally; see `docs/progress/STATUS.md` for what was tested.

## What was built, in plain language

Before any business service (orders, payments ...) exists, the platform needs a "front door" and some shared plumbing:

| Piece | One-line purpose |
|---|---|
| **Maven parent POM** | One place that pins versions (Spring Boot, Spring Cloud ...) so every module builds against the same, compatible set. |
| **Docker Compose profiles** | Start only the infrastructure the current step needs (PostgreSQL, Redis, Keycloak) instead of everything. |
| **Keycloak realm `oms`** | The login system: users, roles, and the rules for which apps may ask for tokens. |
| **oms-common-web** | A small library every service reuses so errors, logging and correlation ids look the same everywhere. |
| **config-server** | Hands out configuration to services at startup, so settings live in one place. |
| **discovery-service (Eureka)** | A phone book: services register by name; others look them up instead of hard-coding host:port. |
| **api-gateway** | The only public entry point: checks the JWT, applies rate limits, routes `/api/v1/mobile/**` to the right service. |

How a request flows: client logs in at Keycloak and gets a short-lived JWT -> calls the gateway with `Authorization: Bearer <JWT>` ->
gateway verifies signature, expiry, issuer, audience and the role -> adds `X-Correlation-Id` -> looks up the target in Eureka -> forwards.
If the target is down, the circuit breaker answers with a friendly 503 in the standard error format.

## Key classes and why

**libs/oms-common-web** (`com.retail.oms.common.web`)
- `ApiError` - the single error body (timestamp, status, errorCode, message, path, traceId, fieldErrors). One format means the Angular app and support staff only learn one shape.
- `OmsException(errorCode, httpStatus, message)` - base class for business errors; each service adds subclasses like `OrderNotFoundException` ("ORD-404").
- `GlobalExceptionHandler` - one `@RestControllerAdvice`. It extends Spring's `ResponseEntityExceptionHandler` so 404/405/415/malformed JSON are handled too; without that, a catch-all `Exception` handler would wrongly turn them into 500s. The generic 500 never reveals the exception message (stack trace goes to the log only).
- `SecurityExceptionHandler`, `ResilienceExceptionHandler` - separate advice beans registered only if Spring Security / Resilience4j are on the classpath. They have a higher `@Order` than the generic handler; otherwise the catch-all would win.
- `CorrelationIdFilter` - reads or creates `X-Correlation-Id`, puts it in the logging MDC, echoes it in the response, always clears the MDC (threads are reused).
- `logback-spring.xml` + `LogMasking` - readable logs locally, JSON logs in `qa/prod/k8s`, e-mail and phone numbers masked.
- `OmsCommonWebAutoConfiguration` - wires the above in automatically, only for servlet apps (the gateway is reactive and has its own filters).

**services/api-gateway** (`com.retail.oms.gateway`)
- `SecurityConfig` - OAuth2 resource server. Builds the JWT decoder from the JWKS URL plus explicit issuer and audience validators; the decoder fetches keys lazily, so the gateway can start before Keycloak.
- `KeycloakRealmRoleConverter` - turns `realm_access.roles` into Spring `ROLE_*` authorities.
- `CorrelationIdWebFilter`, `AccessLogWebFilter`, `InternalRequestWebFilter` - correlation id, one access-log line per request, and blocking `/internal/**` plus stripping spoofable `X-User-*`/`X-Internal-*` headers. Run before security so even 401s are covered.
- `GatewayErrorWriter`, `GatewayErrorHandler`, `RateLimitErrorBodyWebFilter` - make gateway-originated errors (401, 403, 404, 503, 429) use `ApiError`. The built-in rate limiter answers 429 with an empty body, so a response decorator fills it in.
- `FallbackController` - target of the circuit breakers.
- `RateLimitConfig.userKeyResolver` - rate-limit bucket per user (JWT subject), per client address when anonymous.
- Routes (`application.yml`) follow architecture section 6.1; Redis-backed `RequestRateLimiter` and `CircuitBreaker` filters.

**services/config-server** - `@EnableConfigServer`; `native` profile reads `config-repo/` from this repo, `git` profile serves the same files from GitHub (tested).
**services/discovery-service** - `@EnableEurekaServer`, standalone (does not register with itself).

## Things that went wrong and what they taught

- A database called `order` is an SQL reserved word -> `CREATE USER order` fails. All databases/users are `oms_<service>`.
- Keycloak refuses a client session limit that is longer than the realm's, so the realm holds the longest value (8 h for agents) and customer clients are shortened.
- In Gateway YAML a comma splits arguments: `Path=/a,/b` inside `[...]` and `AddResponseHeader=Cache-Control, public, max-age=300` were both misparsed. Quote the value or use the `name/args` form.
- Resilience4j opens a circuit only after `minimumNumberOfCalls` (default 100), not after the sliding window size; the design's "50% over 20 calls" needs both set to 20.
- Rate-limit 429s from Spring Cloud Gateway have no body unless you add one.
- Eureka removes a crashed instance after roughly 40 s here (heartbeat 10 s, lease 30 s, 5 s eviction sweep), not exactly 30.
- A malformed `Authorization: Bearer ` header gets 401 even on public paths; sending no header at all is fine.

## Likely interview questions

1. **Why an API gateway? What does yours do?** Single entry point: auth, routing, rate limiting, circuit breaking, correlation id. Clients never learn internal service addresses.
2. **How is a JWT validated? Why validate again in each service?** Signature via the issuer's public keys (JWKS), then `exp`, `iss`, `aud`. Zero trust: a service reachable without the gateway (misconfigured network, another internal caller) must not trust headers.
3. **Why check the `aud` claim?** Stops a token issued for a different API (same Keycloak) being replayed here.
4. **Access vs refresh token lifetimes?** Short access token (5 min) limits damage if stolen; refresh token (30 min / 8 h sliding) avoids frequent logins.
5. **Why PKCE for mobile and SPA?** Public clients can't keep a secret; PKCE binds the authorization code to the client that started the flow.
6. **Client credentials flow - when?** Service-to-service calls with no user; each service has its own confidential client and the `SERVICE` role.
7. **Eureka vs Kubernetes Services?** Kubernetes already does discovery and config (Services, ConfigMaps); Eureka and Config Server are kept here to learn them. Mention the trade-off.
8. **What does the Config Server add? Drawbacks?** Central, versioned config per profile and refresh without redeploy. Drawbacks: another service to run, startup dependency (mitigated with `optional:configserver:`), secrets must still stay out of Git.
9. **Circuit breaker states and your settings?** Closed -> open at 50% failures over 20 calls -> half-open after 30 s; 3 s timeout; fallback returns a friendly 503.
10. **Token-bucket rate limiting in Redis?** `replenishRate` tokens/s, `burstCapacity` bucket size, `requestedTokens` cost per request (chat: cost 6 -> 10 requests/min). Redis makes the limit shared across gateway instances.
11. **Why one error format and a correlation id?** Clients and support see one shape; the id ties a user's complaint to every log line across services.
12. **Why is the `@RestControllerAdvice` ordering important?** The first advice with a matching handler wins; a catch-all `Exception` handler must be last.
13. **Why Spring Cloud Gateway (reactive) rather than MVC?** Non-blocking I/O suits a proxy that mostly waits on other services; trade-off: no thread-bound MDC/ThreadLocals, so the correlation id is carried in the exchange and the log text.
14. **Why a separate management port?** Actuator (health, metrics) stays off the public path and can't be reached through the gateway.
15. **How did you keep `/internal/**` private?** The gateway blocks any path with an `internal` segment (decoded, so `%69nternal` and `..` tricks fail) and each service additionally requires the `SERVICE` role there.
