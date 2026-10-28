# Order Management Platform

> **Personal learning project.** Not production software and not professional work.
> It follows the design in [`docs/architecture/oms-architecture.pdf`](docs/architecture/oms-architecture.pdf)
> to practise Spring Boot / Spring Cloud / Kafka / Keycloak / Kubernetes style microservices.

## Mocked integrations
Every external system is mocked or run locally. Nothing here talks to a real SAP, carrier, tax or email provider.

| External system | Local stand-in | Status |
|---|---|---|
| SAP S/4HANA | Mock IDoc Spring Boot app | planned (step 5) |
| Carriers, tax provider | WireMock | planned (step 4) |
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

## Layout
`libs/oms-common-*` shared code - `services/<name>` independent Spring Boot apps - `config-repo/` served by the Config Server -
`infra/` container setup (PostgreSQL init, Keycloak realm) - `docs/` design, progress, learning notes.

## More
- Progress and next step: [`docs/progress/STATUS.md`](docs/progress/STATUS.md)
- Ports, URLs and logins: [`docs/local-access.md`](docs/local-access.md)
- What each step teaches: [`docs/learning/`](docs/learning/)
