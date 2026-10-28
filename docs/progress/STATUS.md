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
- [ ] 1.5 `services/config-server` (8888, native/Git-backed config)
- [ ] 1.6 `services/discovery-service` (Eureka, 8761)
- [ ] 1.7 `services/api-gateway` (8080: JWT resource server, X-Correlation-Id, block /internal/**, fallback)
- [ ] 1.8 Keycloak realm export `oms` with roles from section 7.3 and clients from 7.1, imported by Compose
- [ ] 1.9 End-to-end check: infra + 3 services up, token from Keycloak, call through gateway
- [ ] 1.10 Docs: `docs/local-access.md`, `docs/learning/step-1-foundation.md`, README, final STATUS; merge `develop` -> `main`

### Done and tested
- `libs/oms-common-web`: 18 unit/integration tests pass (`mvn -pl libs/oms-common-web verify`): ApiError format, OmsException, 400 body/param/malformed JSON, 404, 405, 403, 503 (Resilience4j), 500 without leaking details, correlation id kept/generated/echoed/cleared from MDC, log masking. JSON logging branch (qa/prod/k8s) not yet exercised - check in 1.5.
- Docker Compose profiles `db`, `cache`, `auth`, `foundation`: no profile starts nothing; Postgres (5434) creates 7 `oms_*` DBs+users and answers as `oms_order`; Redis PONG; Keycloak 26.8.1 healthy on 8180 (admin token HTTP 200, schema in `oms_keycloak`). Compose naming gotcha: DB `order` is an SQL reserved word, so all DBs/users are `oms_<service>`.
- Parent POM: Boot 3.5.16, Cloud 2025.0.3, Spring AI 1.1.8, Camel 4.18.5, springdoc 2.8.17 resolve (`mvn -N validate` OK, BOM imports resolved). Plugins (jacoco) are exercised first in 1.4.

### Mocked
(nothing yet)

### Remaining
Steps 2-9 of section 15.6.

### Next step
Task 1.5 (config-server)
