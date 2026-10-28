# Project
Order Management Platform: a personal learning project (not production, not professional work).
The full design is docs/architecture/oms-architecture.pdf. Follow it, with the decisions below.

# Decisions (these override the design document)
- Java 21 (LTS), Spring Boot 3.5.x, Spring Cloud 2025.0.x, Spring AI 1.1.x,
  Apache Camel 4.18.x LTS (verify latest patch versions and compatibility before pinning)
- Angular 21.x, standalone components and signals
- OpenFeign for service-to-service calls, as in the design
- Also include: springdoc OpenAPI (Swagger UI) per service, Lombok, GitHub Actions
  alongside Jenkins, Avro + Schema Registry for Kafka events, Kafka UI for local dev
- One Git repo: services/<service-name>, each an independent Spring Boot app with its own
  database and Flyway migrations; shared code in libs/oms-common-*; Maven parent POM
- Base package com.retail.oms.<service>
- Mocked externals: SAP (mock IDoc app), carriers and tax (WireMock), email/SMS (Mailpit),
  WMS (ActiveMQ Artemis + simulator), PIM/POS (Kafka test producers), Stripe test mode
  or stripe-mock
- Chatbot: Spring AI with Ollama (tool-calling model), not Bedrock
- Kubernetes locally with kind; AWS via Terraform only, never apply without asking me

# Environment
- macOS 27 arm64, 16 GB RAM; Java 21 (OpenJDK 21.0.12); Maven 3.9.16; Node 24.12; Docker 29 (8 GB)
- GitHub account ayyappakammila989 (gh logged in)
- Native PostgreSQL already uses 5432 and another project uses 5433: Docker Postgres uses 5434
- Never run everything at once: use Docker Compose profiles and start only what the
  current step needs; one PostgreSQL container with one database and user per service

# How to work
- Build in the order of section 15.6. Each step must work end to end before the next.
- Split each step into small tasks. After each task: build, run tests, show real output, commit.
- Branches: feature/<short-desc> off develop; merge to develop when tests pass; main at milestones.
- Never invent command output. Never mark anything done that has not been run and tested.
- Keep docs/progress/STATUS.md current (done, tested, mocked, remaining, next step) so a
  new session can resume.
- After each build step, write docs/learning/step-<n>-<name>.md: plain-language explanation
  of what was built, the key classes and why, and likely interview questions.
- README must call this a personal learning project and mark mocked integrations.
- Keep docs/local-access.md current: for every container, the host port, URL, username,
  and how to connect from DBeaver, MongoDB Compass, Redis Insight or the browser.
- Ask me before: installing global tools, changing ~/.zshrc, anything that costs money,
  deleting data.