# Project status

Personal learning project. Build order follows section 15.6 of `docs/architecture/oms-architecture.pdf`.
A task is only marked **done** after it was built/run and the output was checked.

Legend: `[ ]` todo · `[~]` in progress · `[x]` done and tested

## Current step: 1 - Foundation

Goal: config-server, discovery-service, api-gateway, Keycloak, one shared template with error handling and logging.

### Task list
- [x] 1.1 Bootstrap: .gitignore, README, STATUS, GitHub repo (public), branches `main` + `develop`
- [x] 1.2 Maven parent POM (versions pinned and verified), `libs/` and `services/` aggregators
- [x] 1.3 Docker Compose with profiles: PostgreSQL (5434, one DB+user per service), Redis, Keycloak
- [x] 1.4 `libs/oms-common-web`: error format (14.4), `OmsException` + `@RestControllerAdvice` (14.2), correlation id filter + MDC
- [x] 1.5 `services/config-server` (8888, native/Git-backed config)
- [x] 1.6 `services/discovery-service` (Eureka, 8761)
- [x] 1.7 `services/api-gateway` (8080: JWT resource server, X-Correlation-Id, block /internal/**, fallback)
- [x] 1.8 Keycloak realm export `oms` with roles from section 7.3 and clients from 7.1, imported by Compose
- [ ] 1.9 End-to-end check: infra + 3 services up, token from Keycloak, call through gateway
- [ ] 1.10 Docs: `docs/local-access.md`, `docs/learning/step-1-foundation.md`, README, final STATUS; merge `develop` -> `main`

### Done and tested
- Keycloak realm `oms` (`infra/keycloak/oms-realm.json`, imported by Compose): real tokens fetched for customer1/agent1/supervisor1/ops1/finance1 and decoded: iss `http://localhost:8180/realms/oms`, aud `oms-api`, correct roles (supervisor includes SUPPORT_AGENT), access token 300 s, refresh 1800 s for customer clients; client-credentials token for `order-service` has role SERVICE; wrong password -> 400; real clients reject password grant; PKCE S256 confirmed via admin API on mobile-app/web-app/admin-portal. NOT exercised: the browser auth-code+PKCE flow itself and the 8 h admin-portal refresh (needs the Angular app, step 6). Gotcha: Keycloak rejects a client session limit above the realm limit, so the realm holds the 8 h value and customer clients override down to 30 min.
- `services/api-gateway` (WebFlux, 8080, mgmt 18080): 18 tests pass (12 routing/security with injected auth, 6 token validation). Covered: 401/403 in ApiError format, role rules for admin/chat, public webhooks/stores/fallback, `/internal/**` blocked (incl. `..` and `%69nternal`), correlation id generated/kept/forwarded, X-User-*/X-Internal-* stripped, 404/503 in ApiError format, issuer/audience/expiry validation, Keycloak role mapping. Real jar boots with defaults, answers 401/403 and health. NOT yet verified: real Keycloak token (1.9), Redis rate limiter and circuit breaker fallback at runtime (1.9), Eureka registration (1.9), `/actuator/gateway/routes` returned an error body (to investigate).
- `services/discovery-service` (Eureka, 8761, mgmt 18761): 3 tests pass; real jar started: dashboard 200, `/eureka/apps` JSON empty registry, health UP, standard ApiError on 404, correlation id echoed. Self-preservation off + 5s eviction timer for local use. Nothing has registered with it yet (api-gateway in 1.7 will be the first client).
- `services/config-server` (8888, mgmt 18888): 3 tests pass; started the real jar: serves `config-repo/application.yml` for any app name, `application-qa.yml` for profile qa, actuator health UP on 18888, 405 returns the standard ApiError JSON, X-Correlation-Id echoed. With profile `qa` logs are JSON (service + correlationId fields); locally a readable pattern. Quirk: on the app port `/actuator/health` is answered by the config endpoint (app name "actuator"), not by actuator. `git` profile (serve from GitHub) is configured but NOT tested yet.
- `libs/oms-common-web`: 18 unit/integration tests pass (`mvn -pl libs/oms-common-web verify`): ApiError format, OmsException, 400 body/param/malformed JSON, 404, 405, 403, 503 (Resilience4j), 500 without leaking details, correlation id kept/generated/echoed/cleared from MDC, log masking. JSON logging branch verified in 1.5.
- Docker Compose profiles `db`, `cache`, `auth`, `foundation`: no profile starts nothing; Postgres (5434) creates 7 `oms_*` DBs+users and answers as `oms_order`; Redis PONG; Keycloak 26.8.1 healthy on 8180 (admin token HTTP 200, schema in `oms_keycloak`). Compose naming gotcha: DB `order` is an SQL reserved word, so all DBs/users are `oms_<service>`.
- Parent POM: Boot 3.5.16, Cloud 2025.0.3, Spring AI 1.1.8, Camel 4.18.5, springdoc 2.8.17 resolve (`mvn -N validate` OK, BOM imports resolved). Plugins (jacoco) are exercised first in 1.4.

### Mocked
(nothing yet)

### Remaining
Steps 2-9 of section 15.6.

### Next step
Task 1.9 (end-to-end check)
