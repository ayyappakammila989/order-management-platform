# Step 2A - order-service and the transactional outbox

> Personal learning project. This note explains what was built in step 2 part A, why, and what
> an interviewer is likely to ask about it.

## 1. The problem in plain language

When an order is placed, two things must happen:

1. the order is saved in order-service's PostgreSQL database, and
2. the rest of the system is told about it (an `OrderCreated` event on Kafka), so the saga can reserve stock,
   the search index can be updated, the customer can get an email, and so on.

The naive code does both in a row:

```java
orderRepository.save(order);                 // 1. database
kafkaTemplate.send("oms.order.created", e);  // 2. Kafka
```

These are **two different systems**, and there is no transaction that covers both. So things can go wrong in between:

| What fails | Result |
|---|---|
| Kafka is down after the DB commit | Order exists, nobody is told. Stock is never reserved, the customer never gets an email. |
| The app crashes between the two lines | Same: a "ghost" order. |
| Send to Kafka first, then the DB insert fails | An event for an order that does not exist. Payment may even be taken. |

This is the **dual-write problem**. Retrying does not fix it, because the app may die before it gets to retry.

## 2. The outbox idea

Do not talk to Kafka in the request at all. Instead, write the event **into the same database, in the same
transaction** as the order:

```
BEGIN;
  INSERT INTO orders ...;
  INSERT INTO order_lines ...;
  INSERT INTO order_status_history ...;
  INSERT INTO outbox_event (id, aggregate_id, event_type, payload, ...);   -- the event, as a row
COMMIT;
```

A database transaction is all-or-nothing, so either the order **and** its event exist, or neither does.

A separate process then moves rows from `outbox_event` to Kafka. If Kafka is down, rows simply wait.
When Kafka is back, they are sent. Nothing is lost.

We use **Debezium** for that separate process. Debezium does not poll the table. It reads PostgreSQL's
**write-ahead log (WAL)**, the internal journal every committed change goes through. This is called
**change data capture (CDC)**.

```
order-service ──(1 transaction)──> PostgreSQL ──WAL──> Debezium (Kafka Connect) ──> Kafka topic oms.order.created
                                     orders + outbox_event          EventRouter SMT           key = orderId
```

We tried this on the real stack: with Kafka Connect **stopped**, `POST /internal/v1/orders` still returned 201.
PostgreSQL kept the change in the replication slot. After Connect started again, the event appeared in Kafka
about 18 seconds later, exactly once.

### At-least-once, so consumers must de-duplicate

Debezium guarantees *at least once*. After a crash it may re-send events it had already sent but not yet
recorded as sent (its offset). That is why every event carries a unique `id` header (the outbox row id) and why
the design has consumers store `evt:{consumer}:{eventId}` in Redis to ignore duplicates (Part B, order-query-service).

"Exactly once per order" in our tests means **the producer side never creates two events for one change**,
even when the HTTP request is retried. Duplicates caused by Debezium restarts are the consumers' job.

## 3. What was built

| Piece | Where | Why |
|---|---|---|
| Kafka (KRaft), Schema Registry, Kafka Connect + Debezium, Kafka UI | `docker-compose.yml` profiles `kafka`, `cdc`, `order-core` | Local event backbone. KRaft = Kafka without ZooKeeper. Every container has a memory limit (total 4.5 GB, ~2.5 GB used). |
| `wal_level=logical`, user `oms_debezium` | Compose + `infra/postgres/` | Debezium needs logical decoding and a login with REPLICATION. It can only read `outbox_event`. |
| Avro schemas | `libs/oms-common-events/src/main/avro` | The event contract, shared by producers and consumers. Classes are generated at build time. |
| Flyway `V1__init.sql` | order-service | `orders`, `order_lines`, `order_addresses`, `order_status_history`, `outbox_event`, indexes, publication. |
| `Order` aggregate | `domain/Order.java` | Totals computed from lines, the status state machine, one history row per change, `@Version` for optimistic locking. |
| `OrderService` | `application/` | Each use case is one `@Transactional` method that changes the order **and** calls `OutboxWriter`. |
| `OutboxWriter` | `infrastructure/messaging/` | Turns a change into an Avro event and inserts the outbox row. `Propagation.MANDATORY`: it throws if called without a transaction. |
| `IdempotentOrderCreator` + `RedisIdempotencyStore` | `application/`, `infrastructure/redis/` | Makes `POST /internal/v1/orders` safe to retry (section 5). |
| Connector config | `infra/debezium/order-outbox-connector.json` | Debezium Postgres connector + `EventRouter`, registered with `scripts/register-order-outbox-connector.sh`. |
| Security | `config/SecurityConfig.java`, `libs/oms-common-web/.../security/` | Each service validates the JWT again (zero trust), maps Keycloak roles, and checks ownership with `@PreAuthorize`. |
| Contract | `src/test/resources/contracts/cart/*.yml` | What cart-service (Part B) may rely on. Builds a stubs jar for cart-service's tests. |

### How an event travels

1. `OutboxWriter` builds an `OrderCreatedEvent` (Avro) and serializes it with Confluent's `KafkaAvroSerializer`.
   That gives the normal Kafka wire format: byte 0, a 4-byte **schema id** from the Schema Registry, then the Avro
   data. The bytes go into `outbox_event.payload` (`BYTEA`).
2. Debezium sees the INSERT in the WAL. The **EventRouter** transform reshapes it:
   - topic = `oms.` + `event_type` → `oms.order.created`
   - record key = `aggregate_id` (the order id), so all events of one order go to the same partition **in order**
   - headers: `id`, `eventType`, `aggregateType`, `correlationId`
   - value = `payload`, copied byte-for-byte (`ByteArrayConverter`)
3. A consumer uses the ordinary `KafkaAvroDeserializer`, which looks up the schema id in the registry.

**Decision (differs from the design document):** the design stores `payload` as JSONB. JSONB would force either
JSON events, or Debezium inventing an Avro schema from the JSON (money becomes a float, with no real contract).
Storing ready-made Avro bytes keeps the `.avsc` file as the single contract. The cost is that the payload is not
readable in the database. Kafka UI decodes it instead.

### Schema Registry and compatibility
The registry is set to `BACKWARD`: a new schema version must be able to read data written with the old one.
In practice, add new fields **with a default** and never remove or rename a field that consumers read. Money is a
`decimal(18,2)` logical type, so `19.999` is rejected rather than rounded (there is a test for it).

## 4. Key details and the bugs the tests found

- **`save()` on an entity with an assigned id.** Order ids are UUIDs we create ourselves. With a primitive
  `long version`, Spring Data could not tell the order was new, so it called `merge()` instead of `persist()`.
  `merge()` returns a *copy* and leaves the original detached, so the line ids were `null` when the event was
  built. Fix: `Long version` (null means new). Found by `OrderOutboxTransactionIT`; the unit tests could not see it.
- **Fetching two collections in one query.** `@EntityGraph({"lines","addresses"})` joins both, which returns
  lines x addresses rows. With `List` (a Hibernate "bag") the lines came back duplicated (4 instead of 2). Fix:
  `Set` + `@OrderBy`. The test also asserts **one SQL statement** for the details query (no N+1).
- **Debezium heartbeat broke the connector.** Heartbeat records are not outbox rows, so the EventRouter passes
  them through as STRUCTs, and the byte-array converter cannot write a STRUCT. The task died 10 seconds after
  start, and every later event was stuck. Fix: no heartbeat (every write in this database comes with an outbox insert anyway).
  The IT now checks after every test that the connector is still RUNNING.
- **Kafka consumers and new topics.** A consumer that subscribes before the topic exists only notices it at the
  next metadata refresh (`metadata.max.age.ms`, default 5 minutes).
- **Contract tests silently skipped.** On incremental builds the contract plugin skipped re-registering its
  generated test folder, so the tests did not compile and did not run. Found by comparing test counts (56 vs 53).
- **Log masking.** The phone-number mask from step 1 also masked order numbers (`OMS-***`), which would make logs
  useless for support. It now only masks digits that stand alone.

## 5. Idempotency key (why a retry never creates a second order)

cart-service sends `Idempotency-Key: <unique per checkout>`. If the network drops after the order was created,
cart-service retries with the **same key**:

1. Redis `SET idem:http:<key> "IN_PROGRESS|<hash>" NX EX 86400`. `NX` = only if absent, so check and claim are
   one atomic step: of 20 parallel requests exactly one wins (tested against real Redis).
2. On success the value becomes `DONE|<hash>|<orderId>`. A retry gets the **same order** back, with the header
   `Idempotent-Replayed: true`. No second order, no second event.
3. Same key, different body (hash differs) → `422 IDM-422`. Same key while the first request still runs →
   `409 IDM-409`. If creating the order fails, the key is deleted so the client can retry.
4. **Second line of defence:** `orders.idempotency_key` is `UNIQUE`. If Redis is down (500 ms timeout) or lost
   the key, the database still refuses a duplicate, and the service returns the existing order.

## 6. Tests (architecture section 12)

| Section 12 row | Test | Count |
|---|---|---|
| Service layer (create writes outbox event; cancel rejected after shipment) | `OrderServiceTest` (Mockito, fixed `Clock`) | 10 |
| Domain rules / rounding | `OrderTest` (totals, 2-decimal rule, state machine table) | 15 |
| Mappers | `OrderEventMapperTest`, `OutboxWriterTest` (real Avro + mock registry) | 3 |
| Idempotency (second call returns stored result, called once) | `IdempotentOrderCreatorTest` | 6 |
| Exception handler + Controllers (400 with field list, role checks) | `OrderApiTest` (`@WebMvcTest`, real `SecurityConfig`) | 19 |
| Contracts | generated `CartTest` from 3 YAML contracts | 3 |
| Repositories (queries, indexes used, optimistic locking) | `OrderRepositoryIT` (PostgreSQL 17 container, `EXPLAIN`) | 9 |
| Outbox transaction | `OrderOutboxTransactionIT` | 4 |
| Outbox → Kafka (event published once per order) | `OutboxDebeziumIT` (Postgres + Kafka + Schema Registry + Debezium + Redis containers) | 3 |
| Security (real token accepted, wrong role 403) | `KeycloakSecurityIT` (Keycloak 26.8.1 container, project realm) | 5 |
| Redis (TTLs, SETNX) | `RedisIdempotencyStoreIT` | 4 |
| End to end | `scripts/smoke-step2a.sh` on the Compose stack | 32 checks |

Not applicable yet: Kafka consumers + DLQ (order-service consumes nothing in Part A; order-query-service in Part B),
Camel/SAP/SFTP routes (steps 4, 5, 9), Postman/Cypress QA end to end (later steps).

## 7. Interview questions

**Q: What is the dual-write problem and how does the outbox pattern solve it?**
Writing to a database and a message broker in one request cannot be atomic. A crash in between leaves them
inconsistent. The outbox writes the event into a table in the same local transaction as the business change, so
both commit or neither does. A relay (Debezium or a poller) then publishes the rows, so publishing is retried
until it succeeds.

**Q: Why not use a distributed transaction (XA / 2PC) between PostgreSQL and Kafka?**
Kafka does not take part in XA. Even where 2PC is possible, it is slow, blocks while the coordinator is down,
and couples availability: if the broker is down, you cannot take orders. With an outbox, order taking keeps
working when Kafka is down.

**Q: Polling publisher vs CDC (Debezium)?**
Polling: a scheduled job runs `SELECT ... WHERE sent = false`, publishes the rows and marks them sent. It is
simple, but it adds query load and latency, needs locking with several instances (`FOR UPDATE SKIP LOCKED`),
and can reorder events. CDC reads the WAL: low latency, no extra queries, commit order is kept, and the app does
not need to mark rows. The costs are an extra component (Kafka Connect), replication slots to watch (a stopped
connector makes PostgreSQL keep WAL and fill the disk), and `wal_level=logical`.

**Q: Is the outbox exactly-once?**
No, it is at-least-once. The relay can re-send after a crash. Consumers must be idempotent: de-duplicate by
event id, or make the effect naturally idempotent (an upsert by order id with a version check).

**Q: How do you keep the events of one order in order?**
Use the order id as the Kafka key: same key, same partition, and Kafka keeps order within a partition. Debezium
publishes in WAL (commit) order. Consumers must process one partition sequentially.

**Q: When can outbox rows be deleted?**
With CDC, right after commit: Debezium reads the WAL, not the table. We keep 7 days for debugging and delete them
with a scheduled job. The publication covers inserts only, so the deletes produce no events.

**Q: What does `Propagation.MANDATORY` do on `OutboxWriter`?**
It fails if no transaction is active. Writing the outbox row in a different transaction than the order would
bring back the dual-write problem, so this turns a silent design bug into an immediate exception.

**Q: How is the event schema evolved without breaking consumers?**
Avro + Schema Registry with BACKWARD compatibility: add optional fields with defaults; do not remove or rename
fields consumers read; for a breaking change, publish a new topic or version and migrate consumers.

**Q: How does the idempotency key work, and why both Redis and a unique column?**
Redis `SET NX EX` atomically claims the key and remembers the result for 24 h, so retries get the same answer
fast. The unique column guarantees correctness even if Redis loses data or is down. Redis gives speed and nice
responses; the database gives the guarantee.

**Q: What is optimistic locking and where did you use it?**
A `@Version` column. Each UPDATE includes `WHERE version = ?`. If someone else changed the row first, zero rows
match and Hibernate throws `OptimisticLockException`. Used on `orders`, so a cancel and a saga confirmation
cannot both win silently. It was tested with two real transactions.

**Q: How do you avoid N+1 queries when loading an order?**
`@EntityGraph(attributePaths = {"lines", "addresses"})`, or `JOIN FETCH`. Use `Set` for the collections (two
`List`s cause `MultipleBagFetchException`, and a `List` joined with a second collection returns duplicates).
`open-in-view=false`, so lazy loading cannot hide in the JSON rendering. The test counts SQL statements.

**Q: Why does each service validate the JWT again if the gateway already did?**
Zero trust: internal calls might not come through the gateway (another service, a misconfigured route, an
attacker inside the network). Each service checks signature, expiry, issuer and audience, and applies its own
rules (for example, a customer can only read their own order).

**Q: What does a consumer-driven contract test give you?**
The provider (order-service) runs tests generated from the contract on every build, so it cannot break the API
cart-service depends on without a red build. The consumer tests against stubs generated from the same contract,
so it never assumes behaviour the provider does not have.
