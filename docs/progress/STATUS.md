# Project status

Personal learning project. Build order follows section 15.6 of `docs/architecture/oms-architecture.pdf`.
A task is only marked **done** after it was built/run and the output was checked.

Legend: `[ ]` todo · `[~]` in progress · `[x]` done and tested

## Step 1 - Foundation: COMPLETE (merged to `main`)

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
- [x] 1.9 End-to-end check: infra + 3 services up, token from Keycloak, call through gateway
- [x] 1.10 Docs: `docs/local-access.md`, `docs/learning/step-1-foundation.md`, README, final STATUS; merge `develop` -> `main`

### Done and tested
- **End-to-end (task 1.9)**: with Postgres/Redis/Keycloak in Docker and config-server, discovery-service and api-gateway running as jars, `scripts/smoke-step1.sh` passed 22/22 checks: health of all pieces; gateway fetched its config from the Config Server and registered in Eureka; real Keycloak tokens: no token/tampered/wrong-realm token -> 401, customer on admin/chat -> 403, agent on admin/chat -> passes auth then 503 fallback (backends do not exist yet), public webhooks/stores reachable without token, `/internal/**` -> 403 even for a supervisor, ApiError body with traceId = X-Correlation-Id, Redis-backed rate limit: 10 chat calls pass, the 11th gets 429 with the ApiError body (keys visible in Redis).
- Keycloak realm `oms` (`infra/keycloak/oms-realm.json`, imported by Compose): real tokens fetched for customer1/agent1/supervisor1/ops1/finance1 and decoded: iss `http://localhost:8180/realms/oms`, aud `oms-api`, correct roles (supervisor includes SUPPORT_AGENT), access token 300 s, refresh 1800 s for customer clients; client-credentials token for `order-service` has role SERVICE; wrong password -> 400; real clients reject password grant; PKCE S256 confirmed via admin API on mobile-app/web-app/admin-portal. NOT exercised: the browser auth-code+PKCE flow itself and the 8 h admin-portal refresh (needs the Angular app, step 6). Gotcha: Keycloak rejects a client session limit above the realm limit, so the realm holds the 8 h value and customer clients override down to 30 min.
- `services/api-gateway` (WebFlux, 8080, mgmt 18080): 20 tests pass (12 routing/security with injected auth, 6 token validation, 2 rate-limit error body). Covered: 401/403/404/429/503 in ApiError format, role rules for admin/chat, public webhooks/stores/fallback, `/internal/**` blocked (incl. `..` and `%69nternal`), correlation id generated/kept/forwarded, X-User-*/X-Internal-* stripped, issuer/audience/expiry validation, Keycloak role mapping. Runtime behaviour is covered by the end-to-end entry above.
- `services/discovery-service` (Eureka, 8761, mgmt 18761): 3 tests pass; real jar started: dashboard 200, `/eureka/apps` JSON empty registry, health UP, standard ApiError on 404, correlation id echoed. Self-preservation off + 5s eviction timer for local use. api-gateway registers with it (see end-to-end). A hard-killed instance (`kill -9`) disappeared after ~41 s (heartbeat 10 s, lease 30 s, 5 s sweep).
- `services/config-server` (8888, mgmt 18888): 3 tests pass; started the real jar: serves `config-repo/application.yml` for any app name, `application-qa.yml` for profile qa, actuator health UP on 18888, 405 returns the standard ApiError JSON, X-Correlation-Id echoed. With profile `qa` logs are JSON (service + correlationId fields); locally a readable pattern. Quirk: on the app port `/actuator/health` is answered by the config endpoint (app name "actuator"), not by actuator. `git` profile tested: served `config-repo/application.yml` from GitHub (`develop`, commit 2d64f13).
- `libs/oms-common-web`: 18 unit/integration tests pass (`mvn -pl libs/oms-common-web verify`): ApiError format, OmsException, 400 body/param/malformed JSON, 404, 405, 403, 503 (Resilience4j), 500 without leaking details, correlation id kept/generated/echoed/cleared from MDC, log masking. JSON logging branch verified in 1.5.
- Docker Compose profiles `db`, `cache`, `auth`, `foundation`: no profile starts nothing; Postgres (5434) creates 7 `oms_*` DBs+users and answers as `oms_order`; Redis PONG; Keycloak 26.8.1 healthy on 8180 (admin token HTTP 200, schema in `oms_keycloak`). Compose naming gotcha: DB `order` is an SQL reserved word, so all DBs/users are `oms_<service>`.
- Parent POM: Boot 3.5.16, Cloud 2025.0.3, Spring AI 1.1.8, Camel 4.18.5, springdoc 2.8.17 resolve (`mvn -N validate` OK, BOM imports resolved). Plugins (jacoco) are exercised first in 1.4.

### Mocked
(nothing yet - no external systems involved in step 1)

### Remaining
Steps 2-9 of section 15.6. Known gaps from step 1:
- `/actuator/gateway/routes` returns 404 on the management port even with `management.endpoint.gateway.access` set (3 attempts; routing itself is proven by the smoke test). Not needed now; revisit in step 8 (ops).
- Circuit breaker thresholds are configured (50% / 20 calls / 30 s open / 3 s timeout) and the fallback response is verified, but the OPEN state transition was not observed at runtime.
- Browser Authorization-Code + PKCE login and the 8 h admin-portal refresh are configured, not exercised (needs the Angular app, step 6).
- Services are not containerised yet (run on the host); no GitHub Actions workflow or Jenkinsfile yet; JaCoCo reports are produced but the 80% gate is not enforced yet.
- Springdoc/Swagger UI is for business services; not added to config-server, discovery-service or api-gateway.
- Config Server and Eureka are unauthenticated (local only).

### Next step
Step 2 - Order core (cart-service, order-service with outbox, pricing-service without tax, order-query-service on Elasticsearch).
Needs Kafka (KRaft) + Schema Registry + Debezium/Kafka Connect, Elasticsearch, Kafka UI as new Compose profiles. Ask before installing anything global.
