# Project status

Personal learning project. Build order follows section 15.6 of `docs/architecture/oms-architecture.pdf`.
A task is only marked **done** after it was built/run and the output was checked.

Legend: `[ ]` todo · `[~]` in progress · `[x]` done and tested

## Step 2 - Order core: IN PROGRESS (Part A done, Part B not started)

Split agreed with the owner: **Part A** = Kafka infrastructure + order-service with outbox (done, on `develop`).
**Part B** = cart-service, pricing-service (no tax), order-query-service on Elasticsearch: start only after the owner says "go".

### Part A task list
- [x] 2A.1 Compose profiles `kafka`, `cdc`, `order-core`: Kafka 4.3.2 KRaft, Schema Registry 8.2.4, Debezium Connect 3.6.2.Final, Kafka UI v1.5.0; mem limits; Postgres `wal_level=logical`; `oms_debezium` login
- [x] 2A.2 `libs/oms-common-events`: Avro schemas (OrderCreatedEvent, OrderStatusChangedEvent, Money), Topics, EventHeaders
- [x] 2A.3 order-service persistence: Flyway V1, entities, repositories
- [x] 2A.4 Use cases + OutboxWriter (Avro in the same transaction), mapper, exceptions
- [x] 2A.5 REST API, JWT security (shared helpers in oms-common-web), Idempotency-Key (Redis + unique column), MapStruct, springdoc
- [x] 2A.6 Debezium outbox connector (`infra/debezium/order-outbox-connector.json`) + registration script
- [x] (fix) oms-common-web log masking no longer masks order numbers/UUIDs
- [x] 2A.7 Security IT with real Keycloak; Spring Cloud Contract (producer) for cart-service
- [x] 2A.8 End-to-end smoke test on the Compose stack (`scripts/smoke-step2a.sh`)
- [x] 2A.9 Docs: `docs/learning/step-2a-order-outbox.md`, local-access, README, this file

### Done and tested (Part A)
- **End to end** (2A.8): `order-core` containers + config-server + discovery-service + order-service jars + connector:
  `scripts/smoke-step2a.sh` **32/32 PASS** (health, Swagger, Eureka registration, Flyway, connector RUNNING, idempotent
  create incl. replay header / 422 different body / 400 no key / Redis TTL / one DB row, 401/403 with real Keycloak tokens,
  cancel + repeat no-op, history + outbox rows, exactly one `oms.order.created` and one `oms.order.cancelled` event decoded
  via Schema Registry, key + correlationId header, subjects, 3 partitions, Kafka UI). First run was 31/32: Eureka's
  30 s response cache hid the new instance; the check now waits.
  **Outage test (manual):** Kafka Connect stopped -> order still created (201), slot inactive with WAL retained ->
  Connect started -> event in Kafka exactly once ~18 s later.
- **order-service tests**: `mvn -pl services/order-service verify` -> **56 unit/slice + 25 integration, all pass**:
  OrderServiceTest 10, OrderTest 15, OrderEventMapperTest 1, OutboxWriterTest 2, IdempotentOrderCreatorTest 6,
  OrderApiTest 19 (@WebMvcTest, real SecurityConfig), contract CartTest 3; OrderRepositoryIT 9 (one SELECT for details,
  EXPLAIN shows indexes, optimistic lock, publication), OrderOutboxTransactionIT 4 (commit/rollback together, MANDATORY),
  RedisIdempotencyStoreIT 4 (SETNX, 24 h TTL, 1 of 20 parallel wins), OutboxDebeziumIT 3 (real Debezium + Kafka +
  Schema Registry: once per order despite 3 identical requests, headers, cancel topic, subjects), KeycloakSecurityIT 5
  (real tokens; no/tampered/forged-with-unknown-key token -> 401). KeycloakSecurityIT first passed alone, then failed in
  a full run with "HTTPS required" (realm sslRequired=external + Docker Desktop client address); it now imports a
  test-only realm copy with sslRequired=none.
- **Libraries**: oms-common-web 24 tests (adds OmsJwt/ApiErrorSecurityHandlers tests, masking tests); oms-common-events 4.
- **Compose** (2A.1): all 7 `order-core` containers healthy; Kafka produce/consume, KRaft quorum, Schema Registry BACKWARD,
  Debezium Postgres plugin present, Kafka UI cluster ONLINE with Connect + Schema Registry; fresh-volume init checked on a
  throwaway container. Memory: ~2.2 GB idle, ~2.5 GB under the smoke test (limits sum to 4.5 GB).
- Full reactor `mvn install` green: 135 tests (common-web 24, events 4, config 3, discovery 3, gateway 20, order-service 56 + 25 IT).

### Decisions in Part A (differ from or add to the design document)
- `outbox_event.payload` is **BYTEA with Avro in Confluent wire format**, not JSONB: keeps the `.avsc` as the only contract
  (JSONB would mean schema-less JSON or Debezium-inferred Avro with float money). Debezium copies bytes (`ByteArrayConverter`).
- Extra columns: `orders.idempotency_key` (UNIQUE, second idempotency guard), `orders.discount_total`, `outbox_event.correlation_id`.
- Order statuses for now: PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED (fulfillment/returns steps may add more).
- Status events use one schema `OrderStatusChangedEvent` on topics `oms.order.<status>`; events carry only the ship-to ZIP, not the address.
- Idempotency replay returns the **same order** (current state), not a byte copy of the first response.
- Client libs: Confluent serializer **7.9.9** + Avro 1.11.5 (Kafka 3.9 line = Boot 3.5's kafka-clients 3.9.2), talking to
  the 8.2.4 registry. testcontainers-keycloak 3.9.1 (last on Testcontainers 1.x). Debezium's testcontainers helper needs
  Testcontainers 2.x, so the IT uses plain `GenericContainer`s.
- No Debezium heartbeat (its STRUCT records crash the byte-array converter); every write to `oms_order` includes an outbox insert.
- Ports: order-service 8102 (mgmt 18102). Planned for Part B: cart 8101, pricing 8103, order-query 8104.

### Mocked (Part A)
Nothing external. Kafka, Schema Registry, Debezium, Keycloak, Redis, PostgreSQL are real containers.

### Known gaps / follow-ups from Part A
- `Kafka consumers` rows of section 12 (duplicate ignored, DLQ) are not exercised yet: order-service consumes nothing.
  They come with order-query-service (Part B).
- Gateway has no route to order-service (by design: clients go through BFFs). The internal API is called directly on 8102.
- Replication slot growth is not monitored yet (step 8, ops): a stopped connector makes PostgreSQL keep WAL.
- The phone mask also hides IPv4 addresses in logs (e.g. Eureka instance id) - acceptable, noted.
- JaCoCo reports are produced; the 80% gate is still not enforced. No GitHub Actions/Jenkins pipeline yet (step 8).

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
Step 2 **Part B** (waits for the owner's "go"): cart-service (Redis carts + PostgreSQL saved carts, checkout calls
pricing-service and order-service via OpenFeign, consumer side of the order-service contract with Stub Runner),
pricing-service (promotions, no tax yet, Redis cache), order-query-service (consumes `oms.order.*` into Elasticsearch
`orders-v1`, de-duplication by event id, DLQ; adds an Elasticsearch Compose profile). Then merge `develop` -> `main`
at the end of step 2.
