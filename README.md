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

## Progress
See [`docs/progress/STATUS.md`](docs/progress/STATUS.md). Local ports and logins: [`docs/local-access.md`](docs/local-access.md) (added in step 1).
