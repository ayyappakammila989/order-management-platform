# Order Management Platform

> **Personal learning project.** Not production software and not professional work.
> It follows the design in [`docs/architecture/oms-architecture.pdf`](docs/architecture/oms-architecture.pdf)
> to practise Spring Boot / Spring Cloud / Kafka / Keycloak / Kubernetes style microservices.

## Mocked integrations
Every external system is mocked or run locally. Nothing here talks to a real SAP, carrier, tax or email provider.

| External system | Local stand-in | Status |
|---|---|---|
| SAP S/4HANA | Mock IDoc Spring Boot app | planned (step 5) |
| Tax provider (tax-service) | WireMock stub (`--profile tax-stub`), fixed rates per ZIP | **in use** (pricing-service, step 2B) |
| Carriers | WireMock | planned (step 4) |
| Email / SMS | Mailpit | planned |
| WMS | ActiveMQ Artemis + simulator | planned (step 4) |
| PIM / POS | Kafka test producers | planned |
| Stripe | Stripe test mode or stripe-mock | planned (step 3) |
| LLM for chatbot | Ollama (not Bedrock) | planned (step 9) |

## What works today (step 1 - foundation)
Config Server, Eureka discovery, an API gateway (JWT validation, role rules, Redis rate limiting, circuit-breaker
fallbacks, standard error format) and a Keycloak realm with the roles from the design. No business services yet.

```bash
docker compose --profile foundation up -d        # PostgreSQL, Redis, Keycloak
mvn -DskipTests package
(cd services/config-server    && java -jar target/config-server-0.1.0-SNAPSHOT.jar &)
(cd services/discovery-service && java -jar target/discovery-service-0.1.0-SNAPSHOT.jar &)
(cd services/api-gateway      && java -jar target/api-gateway-0.1.0-SNAPSHOT.jar &)
scripts/smoke-step1.sh                            # 22 end-to-end checks
```

## What works today (step 2 part A - order core)
order-service with the **transactional outbox**: an order and its event are written in one PostgreSQL transaction,
Debezium streams the event from the WAL to Kafka (`oms.order.created`, `oms.order.cancelled`, ...) as Avro with
the Schema Registry. Orders are created idempotently (`Idempotency-Key`, Redis + unique DB key), secured with
Keycloak tokens and role/owner rules. Kafka (KRaft), Schema Registry, Kafka Connect/Debezium and Kafka UI run as
Compose profiles. See [`docs/learning/step-2a-order-outbox.md`](docs/learning/step-2a-order-outbox.md) and the
"Step 2A" section of [`docs/local-access.md`](docs/local-access.md) to run it (`scripts/smoke-step2a.sh`, 32 checks).

## What works today (step 2 part B - cart, pricing, order search)
- **cart-service**: carts in Redis (optimistic versioning, 7-day sliding expiry), saved carts in PostgreSQL, and a checkout that
  prices the cart with pricing-service and creates the order in order-service with the client's `Idempotency-Key`. A retry
  returns the same order even after the cart is gone.
- **pricing-service**: base prices, automatic SKU promotions, coupons, tax, shipping, in whole cents. Tax rates come from a
  **mocked tax-service (WireMock)**, cached in Redis; the catalog and promotions are **mock seed data**, not a PIM feed.
- **order-query-service**: consumes `oms.order.*` events into Elasticsearch (`orders-v1`) with an idempotent consumer,
  retry with backoff and a dead-letter topic, and serves order lookup and search.
- Run and check: [`docs/local-access.md`](docs/local-access.md) ("Step 2B"), `scripts/smoke-step2b.sh` (48 checks);
  explanation in [`docs/learning/step-2b-cart-pricing-query.md`](docs/learning/step-2b-cart-pricing-query.md).
Not there yet: inventory pre-check at checkout, the saga after `oms.order.created` (step 3), payment, UI.

## Layout
`libs/oms-common-*` shared code (`-web`: errors, logging, JWT helpers; `-events`: Avro event contracts) - `services/<name>` independent Spring Boot apps - `config-repo/` served by the Config Server -
`infra/` container setup (PostgreSQL init, Keycloak realm, Debezium connector) - `docs/` design, progress, learning notes.

## More
- Progress and next step: [`docs/progress/STATUS.md`](docs/progress/STATUS.md)
- Ports, URLs and logins: [`docs/local-access.md`](docs/local-access.md)
- What each step teaches: [`docs/learning/`](docs/learning/)
