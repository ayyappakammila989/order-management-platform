# Step 2B: cart, pricing and the order read model

Personal learning project. This note explains what was built in step 2 part B, why, and what an interviewer might ask.

## The picture

```
customer ──► cart-service ──► pricing-service ──► tax-service (WireMock stub)
                  │                                   
                  └──► order-service ──(outbox + Debezium)──► Kafka oms.order.*
                                                                   │
                                          order-query-service ◄────┘ ──► Elasticsearch orders-v1
```

- **cart-service** owns the *shopping* state: what is in the cart. It does not know prices.
- **pricing-service** turns "SKU + quantity + ZIP + coupon" into final money: prices, discounts, tax, shipping.
- **order-service** (step 2A) owns the *order record*.
- **order-query-service** is the **read side** (CQRS): it listens to order events and keeps a search-friendly copy in
  Elasticsearch. It can be slow, rebuilt or lost without losing any order.

## cart-service

**Carts live in Redis** (`cart:{cartId}`, 7 days, sliding). They are short-lived and read/written constantly; saved carts
("save for later") go to PostgreSQL because they must survive.

**Key classes**
- `Cart` / `CartItem` (records): immutable. Every change returns a new cart.
- `RedisCartStore`: stores the cart as JSON plus a version number in one Redis hash. Writing uses a small **Lua script**
  that only writes if the version is still the one we read (compare-and-set). `CartService.change` retries when it loses.
  Why: two browser tabs adding items at the same time must not overwrite each other. `RedisCartStoreIT` proves it with
  8 and 40 parallel writers.
- `CheckoutService`: the interesting one (below).
- `FeignSecurityConfig`: every call to another service carries a **client-credentials token** (cart-service logs in to
  Keycloak as itself, role `SERVICE`) and the correlation id.
- `FeignErrorConfig`: a rejection from pricing/order (unknown SKU, bad coupon) is passed on to the customer with its error
  code; anything else (5xx, our own token being refused) becomes a plain 503.

**Checkout and the Idempotency-Key**
1. If we already know this key (`checkout:{customer}:{key}` in Redis): return the stored order (cart may be gone).
2. Price the cart with pricing-service; build the order request.
3. **Freeze** that request in Redis under the key (SETNX), then call order-service with the same key.
4. Store the created order next to it and delete the cart.

Why freeze? If the order call times out and the client retries, prices may have changed in between. Re-pricing would send a
*different body* with the *same key*, and order-service would (correctly) answer 422. With the frozen request the retry is
byte-identical. Why store the order? After step 4 the cart is deleted, so a retry would otherwise be "cart not found".

**Contract with order-service**: order-service publishes stubs generated from its contracts
(`order-service/src/test/resources/contracts/cart`). `OrderContractConsumerIT` runs the real Feign client against them with
Stub Runner. If order-service changes the API in a way cart-service cannot live with, this test fails first.

## pricing-service

- `PriceCalculator` is pure arithmetic (no I/O) so it can be tested hard: whole cents, `BigDecimal`, HALF_UP.
  A coupon discount is split over the lines with the **largest remainder method** so the pieces add up to the cent.
  A test builds 3000 random carts and checks the identities (sum of line discounts = discount total, grand total =
  sum of lines + shipping, no line goes negative).
- Order of rules: best automatic SKU promotion per line -> one coupon on the discounted total -> tax per line on what is
  left -> shipping (free from 75.00).
- **Redis caches** exactly as designed: `price:{sku}` 10 min, `promo:active` 5 min, `tax:{zip}:{taxCode}` 24 h.
  Redis is only an accelerator: if it is down, the code behaves as if the cache were empty (`PricingCache` swallows the
  error), and `PricingQuoteIT` pauses the Redis container to prove it.
- **Tax** comes from tax-service through OpenFeign behind a **Resilience4j circuit breaker**. The 24 h cache doubles as the
  fallback: a tax outage only hurts ZIP codes not seen in the last day. With no cache and no answer we return 503 and
  **never guess a tax rate**. tax-service is a **WireMock stub** for now (fixed rate per ZIP; ZIP 99999 returns 503).

## order-query-service (the consumer)

**Idempotent consumer** (`OrderEventProcessor`): every event carries an `eventId`. Before applying an event we check
`evt:order-query:{eventId}` in Redis; after applying it we write the key (7 days). Order matters: mark *after* success, so
a crash in between causes a re-delivery rather than a lost event.

But Redis is only a shortcut. The real guarantee is that **the Elasticsearch writes are idempotent too**
(`ElasticsearchOrderIndex`): they are *scripted upserts* (a small Painless script runs inside Elasticsearch):
- the status history is keyed by event id, so applying the same event twice adds nothing;
- an older status never overwrites a newer one (compared by event time);
- no read-modify-write race: Elasticsearch runs the script atomically on the document.

**Out-of-order events**: `created` and `cancelled` are different topics, so Kafka does not order them against each other.
A cancellation can arrive first. The script creates a partial document and the later `created` event fills in the rest
without bringing the status back to PENDING. Tested with real Elasticsearch.

**Retry, backoff, dead-letter topic** (`KafkaConsumerConfig`): Spring's `DefaultErrorHandler` retries a failing record with
exponential backoff (1 s, 2 s, 4 s, 8 s), then `DeadLetterPublishingRecoverer` sends it to `oms.order-query.dlq` with
headers saying where it came from and why. Errors that retrying cannot fix skip the retries: a **poison pill** (bytes that
are not Avro, `DeserializationException` via `ErrorHandlingDeserializer`) and an invalid event (`InvalidEventException`).
The consumer never gets stuck on one bad record. DLQ values are readable text, not Avro, because Avro events of different
types on one topic would clash in the Schema Registry.

**The tests for this are real** (`OrderEventsKafkaIT`: real Kafka, Schema Registry, Elasticsearch, Redis):
duplicate delivered 3 times -> applied once, counted as duplicate; Redis key deleted and event re-sent -> applied again
but the document is unchanged; garbage bytes -> DLQ, no retries, next event processed; invalid status -> DLQ after exactly
one attempt; **Elasticsearch container paused** -> 4 attempts with backoff, then DLQ, consumer carries on after unpausing;
short pause -> retries succeed, nothing dead-lettered.

**Strict mapping**: the index uses `dynamic: strict`, so a typo in a field name is an error instead of a silently wrong
mapping. The index is created at startup, *before* any listener starts, otherwise the first write would make
Elasticsearch invent an index named `orders`.

## Things that went wrong and what they taught

- **Mocking hid a bug.** With the Feign circuit breaker on, every exception from a Feign call comes back wrapped in
  `NoFallbackAvailableException`. Unit tests with mocked clients passed; the contract test with the real client failed.
  Lesson: test the real client at least once.
- **A stub cannot say "header absent".** The "no Idempotency-Key" contract matched requests of other contracts. Fixed by
  giving it its own customer id.
- **Two Spring contexts = two consumers.** In the integration tests two classes with different setup got two cached contexts,
  both joining the same Kafka consumer group, so each saw only some partitions and counters stayed 0 at random.
  Lesson: share one base class for tests that start a Kafka listener.
- **A timeout is not a failure.** When Elasticsearch was paused, the write that "timed out" was applied once it woke up.
  This is why the writes must be idempotent: replaying a dead-lettered event is safe either way.
- **Two swagger jars.** Confluent's client brings an old `swagger-annotations`; depending on classpath order
  `/v3/api-docs` returned 500 in one service. Excluded centrally in the parent POM.

## Likely interview questions

1. Why are carts in Redis and not in a database? What do you lose, and how do you limit it? (TTL, saved carts in PostgreSQL.)
2. Two requests change the same cart at once. What happens? (Version check in Lua, retry.)
3. How does a client safely retry a checkout? Why store the priced request, not just the key?
4. What is the difference between at-most-once, at-least-once and exactly-once? Which one is this consumer, and how did you
   make it behave like exactly-once? (At-least-once + idempotent processing.)
5. Events for one order can arrive out of order. How do you handle it? (Event-time guard in the script; partial documents.)
6. What goes to the dead-letter topic and what does not? Why no retry for a poison pill?
7. Why a dead-letter topic with text values and not Avro?
8. What does the circuit breaker around tax-service do, and why is cached tax a good fallback but guessing is not?
9. What is CQRS, what does it cost you (eventual consistency, a second store to keep correct) and what does it buy?
10. Why scripted upserts instead of read-then-write?
11. What is a consumer-driven contract test and what did Stub Runner do here?
12. Why `dynamic: strict` in the Elasticsearch mapping? What is an alias for?
13. How would you rebuild the index from scratch? (New consumer group or reset offsets, `earliest`; the writes are idempotent.)
