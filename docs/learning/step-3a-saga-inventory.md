# Step 3A: inventory and the order saga

Personal learning project. This note explains what was built in step 3 part A, why, and what an interviewer might ask.
Part B (payments, fraud, webhooks, refunds) follows later and plugs into the same saga.

## The picture

```
cart-service --(pre-check: GET availability)--> inventory-service
     |
     v
order-service --(outbox + Debezium)--> oms.order.created
                                            |
                                            v
                                   order-orchestrator  (saga state + step log in oms_saga)
                                     |            ^
               oms.inventory.reserve-cmd          | oms.inventory.reserved / reserve-failed / released
                                     v            |
                                   inventory-service  (stock, reservations in oms_inventory)
                                            |
order-orchestrator --PATCH status CONFIRMED / CANCELLED--> order-service --> oms.order.confirmed / cancelled
```

A **saga** is a business transaction spread over several services, each with its own database. There is no distributed
transaction (no two-phase commit). Instead every step is a local transaction, and if a later step fails, the earlier ones
are undone by **compensating** actions (release the stock, void the payment, cancel the order).

This project uses **orchestration**: one service (order-orchestrator) knows the steps and tells the others what to do.
The alternative, **choreography**, has each service react to the others' events with nobody in charge; it is simpler
for two steps and hard to follow for five, and "where is my order stuck?" has no single answer.

## First, the step 2 follow-up: registry errors and replay

In step 2 one event went to the dead-letter topic with "Schema 1 not found" although the schema existed: the Schema
Registry had just started. The consumer used Spring's `ErrorHandlingDeserializer`, which turns *every* deserialization
failure into the same exception, and that exception is "not retryable". So a registry hiccup and real garbage bytes were
treated the same.

Now the consumer receives the **raw bytes** and decodes them in the listener (`AvroEventDecoder`, in `libs/oms-common-kafka`):
- the cause chain contains a `RestClientException` (e.g. 404 schema not found), a network error or a Kafka `RetriableException`
  -> `SchemaRegistryUnavailableException` -> retried with its own, longer backoff (about 2 minutes);
- anything else (no magic byte, corrupt Avro) -> `PoisonPillException` -> dead-lettered at once.

A side effect that matters: the dead-letter topic now holds **exactly the bytes that arrived**, also for events that failed
later (e.g. Elasticsearch down). Before, those were stored as JSON text, and replaying them would have created a poison
pill. `scripts/replay-dlq.sh` (class `DlqReplay`) puts records back on their original topic with the same key, value and
headers; replaying twice is harmless because the consumers are idempotent.

## inventory-service

**Data**: `location` (DCs and stores with a ZIP), `stock` (one row per SKU and location: `on_hand`, `reserved`,
`safety_stock`, `version`), `reservation` + `reservation_line` (which units of which location an order holds), plus the
outbox and `processed_message` tables. Available = on hand - reserved - safety stock.

**Reserving** (`ReservationService.reserve`), all lines or nothing, in one short transaction:
1. Has this command id been handled? (`processed_message`, same transaction) -> skip.
2. Does the order already hold an ACTIVE reservation? -> answer with it (a unique partial index guarantees one per order).
3. For each SKU, **in a fixed order** (sorted), lock its rows that still have stock with `SELECT ... FOR UPDATE SKIP LOCKED`.
4. Rank the locations (nearest ZIP prefix, DC before store, most stock) and take the quantity, splitting if needed.
5. Not enough? A **second pass** locks *all* rows of the SKU and waits, but only up to `lock_timeout` (2 s).
6. Write the reservation, raise `reserved`, write the reply and `inventory.changed` events to the outbox. Commit.

Why **SKIP LOCKED**? A popular SKU is a "hot row": with plain `FOR UPDATE` every order queues behind the one holding the
lock. With SKIP LOCKED an order simply takes the stock from another location. Why the second pass? Skipping alone would
say "out of stock" when the stock is merely locked by someone else for a few milliseconds. Why a fixed SKU order? Two
orders locking SKU-A then SKU-B and SKU-B then SKU-A would deadlock while waiting in the second pass.

**Optimistic locking** (`@Version`) is used where rows are *not* locked:
- **Stock snapshots** (absolute counts from the warehouse/POS) read the row, set `on_hand`, and Hibernate writes
  `UPDATE ... WHERE version = ?`. If a reservation changed the row in between, the update finds nothing, and
  `OptimisticRetry` runs the whole snapshot again from a fresh read. No lost update, no lock held while reading.
- **Ending a reservation** can happen three ways at once: a release command, the order being cancelled, the expiry job.
  The reservation's version decides: the first commit wins, the others fail, retry, and see it is no longer ACTIVE.
  `ReservationIT` races release against expiry 10 times; they really collided in all 10 rounds, and stock was released once.

**Expiry**: a reservation lives 30 minutes; when `oms.order.confirmed` arrives the expiry is cleared (the stock is held until
shipment or cancellation). A scheduled job releases expired ones, picking them with SKIP LOCKED so two instances never take
the same reservation. This catches sagas that got stuck.

**Release on cancel**: inventory-service also listens to `oms.order.cancelled` and releases the order's stock by itself.
The saga may send a release command too; whichever comes second finds nothing to release (and says so).

**Snapshots** arrive as `StockSnapshotEvent` on `oms.inventory.snapshot` (for now from `scripts/send-stock-snapshot.sh`, a
mock of the SFTP file / POS feed). A snapshot older than the last one applied for that location is ignored
(`location_snapshot`, an upsert with `WHERE taken_at < EXCLUDED.taken_at`). Reserved quantities stay: reserved units are
still on the shelf until they ship. If a count is lower than what is reserved, the row is "oversold" and logged.

**Availability** (`GET /api/v1/inventory/{sku}/availability?zip=`) is cached in Redis (`atp:{sku}:{zip}`, 30 s) and the
cache entries of a SKU are deleted **after the commit** of any change (`TransactionSynchronization.afterCommit`), so a
rolled-back reservation never evicts and a committed one always does.

## The transactional outbox, two ways

order-service (step 2) uses **Debezium**: it reads the PostgreSQL write-ahead log and publishes outbox rows. inventory-service
and order-orchestrator use a **polling publisher** (`OutboxRelay`): every 200 ms it takes the oldest unsent rows with
`FOR UPDATE SKIP LOCKED`, sends them, waits for the broker's ack and marks them sent - in one short transaction.

| | Debezium (log tailing) | Polling relay |
|---|---|---|
| Extra infrastructure | Kafka Connect, replication slot, publication | none |
| Latency | ~ms after commit | up to the poll interval |
| Load on the DB | reads the WAL | a query every poll |
| Order | commit order | insert order per instance |

Both are **at-least-once**: a crash after sending but before marking sends the row again. That is why every consumer
de-duplicates (`processed_message` in the same transaction as the effect, or Redis + idempotent writes in order-query).
`OutboxRelayIT` shows two relays draining 200 rows together with every row sent exactly once.

## order-orchestrator: the saga engine

**State** (`oms_saga`): `saga_instance` (status, current step, the command it waits for, a deadline, a JSONB payload with the
order data, step results, completed steps and the compensation queue, and a `version`), and `saga_step_log` (every
transition, for support: "why was my order cancelled?" -> `GET /internal/v1/sagas?orderId=`).

**Each message = one transaction**: mark the message processed, change the saga, append to the step log, write the next
command to the outbox. A crash or retry can never send a command twice for one state change.

**Steps** (`SagaStep`): `start` either finishes right away (`Done`, e.g. the REST call to confirm the order), sends a
command and waits (`AwaitReply(commandId)`), or reports a business failure (`Failed`). Part A's saga:
`RESERVE_STOCK` (Kafka command, compensable: release) -> `CONFIRM_ORDER` (REST PATCH, last, not compensable).
Part B inserts `FRAUD_CHECK` and `AUTHORIZE_PAYMENT` in between.

**Compensation in reverse order**: on failure, the completed compensable steps are queued newest first and undone **one at a
time, each waiting for its own reply**. Only when all are done is the order cancelled (`onAborted`). `SagaEngineTest` uses
three fake steps to show C failing -> B undone -> (reply) -> A undone -> (reply) -> cancel.

**Replies are matched by command id.** A reply for a command the saga is not waiting for changes nothing - unless it reports
that a compensable step succeeded after the saga gave up (a **late reply**): then the step is undone right away.

**Timeouts**: a job looks for sagas past their deadline (SKIP LOCKED). A running step that got no reply counts as failed -
and is compensated too, because it may have happened ("I don't know" must be treated as "maybe yes"). A compensation without
reply is sent again up to 3 times; after that the saga is FAILED and needs a person.

**Technical vs business failures**: order-service answering 409 is a business "no" -> compensate. order-service down, the
database paused, Keycloak unreachable -> throw -> the transaction rolls back -> the consumer retries with backoff. The step
timeout (2 min) must be longer than that retry window (~1 min); the integration test that pauses PostgreSQL showed what
happens otherwise: the timeout job and the retried reply race (both outcomes are consistent, but the order would be cancelled).

## Cart pre-check

Before the order is created, cart-service asks inventory for each SKU. A definite "not enough" -> 409 `CRT-409` and the cart
stays. Inventory down or slow (1 s timeout, 2 retries) -> checkout goes on and a counter goes up: the pre-check is a
courtesy, the **saga is the real check**. The smoke test shows both: two customers racing for the last two units both pass the
pre-check, the saga confirms one order and cancels the other with "Not enough stock".

## Things that went wrong and what they taught

- **The volume that was always empty.** Kafka's image writes to `/tmp` unless `KAFKA_LOG_DIRS` is set, so every container
  re-creation silently lost all topics, the schema registry's store and Debezium's connector. An old event later came back
  with a schema id that now meant another schema. Lesson: test that data survives `--force-recreate`; schema ids are state.
- **Feign cannot PATCH.** The default client is `HttpURLConnection`. Unit tests with a mocked client could never find this;
  the IT with WireMock did. Then Apache HttpClient retried 503s on its own - two retry layers multiply attempts and hide what
  happened, so one was switched off (same reasoning as `max.retries=0` on the registry client).
- **Silent defaults.** config-server found its files by a relative path; started from another folder it served nothing and
  every service quietly ran on built-in defaults. Lesson: make "config missing" loud, or paths absolute.
- **A check that cannot fail.** order-query's Kafka group had another name than configured, and the step 2 smoke check "no
  lag" counted the lag of a group that did not exist (zero). Lesson: assert the thing exists before asserting its value.
- **Spring Kafka is quiet.** `DefaultErrorHandler` logs only when the retries are used up; until then a failing message is
  invisible. The shared error handler now logs every failed attempt with its root cause.

## Likely interview questions

1. What is a saga? Orchestration vs choreography - when would you pick which?
2. Why not a distributed transaction (2PC/XA) across order, inventory and payment?
3. What makes a compensating action different from a rollback? Can every step be compensated? (Sending an e-mail cannot.)
4. Why compensate in reverse order? What if a compensation fails?
5. How do you make the saga survive a crash of the orchestrator in the middle?
6. A reply arrives twice / late / for a saga that already gave up. What happens?
7. Why is a timed-out step compensated even though you do not know whether it happened?
8. `SELECT ... FOR UPDATE` vs `FOR UPDATE SKIP LOCKED` vs optimistic locking with `@Version`: when each? What can go wrong
   with SKIP LOCKED alone?
9. How do you avoid deadlocks when one transaction locks several rows?
10. How do you prevent overselling the last unit under concurrency? How did you test it?
11. Transactional outbox: why not just send to Kafka after the commit? Debezium vs polling publisher?
12. At-least-once delivery: where can duplicates come from, and how is each consumer idempotent?
13. Why is the cart's availability check only a hint? What happens when inventory-service is down at checkout?
14. Why does inventory-service listen to `oms.order.cancelled` itself instead of waiting for a saga command?
15. How do you tell a transient error (registry down, DB paused) from a permanent one (poison pill)? Why does it matter for
    the dead-letter topic? How do you replay a dead letter safely?
