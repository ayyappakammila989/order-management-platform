# Local access

Everything here is **local development only** (default passwords, no TLS). Start only what you need:

```bash
docker compose --profile db up -d          # PostgreSQL
docker compose --profile cache up -d       # Redis
docker compose --profile auth up -d        # Keycloak (+ PostgreSQL)
docker compose --profile foundation up -d  # all of the above (step 1)
docker compose --profile kafka up -d       # Kafka (KRaft) + Schema Registry + Kafka UI
docker compose --profile cdc up -d         # Kafka + Schema Registry + Kafka Connect/Debezium + PostgreSQL
docker compose --profile search up -d      # Elasticsearch only
docker compose --profile kibana up -d      # Kibana (+ Elasticsearch); optional, not part of order-core
docker compose --profile tax-stub up -d    # WireMock standing in for tax-service
docker compose --profile order-core up -d  # everything step 2 needs (foundation + kafka + cdc + Elasticsearch + tax stub)
docker compose --profile order-core down   # stop (keeps data); `down -v` deletes data
```

Memory: every container has a `mem_limit`; the sum for `order-core` is 6 GB (Docker Desktop has 8 GB). Measured after the
step 2B smoke test with all 9 running: **about 3.7 GB** in total (`docker stats --no-stream`): Elasticsearch 1.0 GB,
Kafka Connect 0.85 GB, Keycloak 0.55 GB, Kafka 0.39 GB, Kafka UI 0.32 GB, Schema Registry 0.29 GB, tax stub 0.17 GB,
PostgreSQL 0.17 GB, Redis 0.01 GB. Kibana adds about 0.63 GB when started (`--profile kibana`); it is left out of
`order-core` to keep Testcontainers runs possible.

## Containers

| Container | Profiles | Host port | URL | Login |
|---|---|---|---|---|
| `oms-postgres` (PostgreSQL 17, `wal_level=logical`) | db, auth, foundation, cdc, order-core | **5434** | `jdbc:postgresql://localhost:5434/<db>` | see below |
| `oms-redis` (Redis 7) | cache, foundation, order-core | 6379 | `redis://localhost:6379` | no password |
| `oms-keycloak` (Keycloak 26.8.1) | auth, foundation, order-core | **8180** (admin UI/OIDC), 9000 (management) | http://localhost:8180 | admin / admin |
| `oms-kafka` (Apache Kafka 4.3.2, KRaft, single node) | kafka, cdc, order-core | **9092** (from the host) | `localhost:9092`; containers use `kafka:29092` | none (PLAINTEXT) |
| `oms-schema-registry` (Confluent 8.2.4) | kafka, cdc, order-core | 8081 | http://localhost:8081/subjects | none |
| `oms-kafka-connect` (Debezium 3.6.2.Final on Kafka Connect) | cdc, order-core | 8083 | http://localhost:8083/connectors | none |
| `oms-kafka-ui` (kafbat/kafka-ui v1.5.0) | kafka, order-core | **8090** | http://localhost:8090 | no login (local only) |
| `oms-elasticsearch` (8.19.19, single node, security off) | search, kibana, order-core | **9200** | http://localhost:9200 | none (local only) |
| `oms-kibana` (8.19.19) | kibana | **5601** | http://localhost:5601 | none (local only) |
| `oms-tax-stub` (WireMock 3.13.2, **mock of tax-service**) | tax-stub, order-core | **8190** | http://localhost:8190/__admin/mappings | none |

Host ports 5432 and 5433 are used by other PostgreSQL instances, so Docker Postgres is on 5434.
Host 8080 is kept free for api-gateway, hence Keycloak on 8180.

### PostgreSQL databases (one database + one user per service)
Name and password are both `oms_<service>`; the superuser is `postgres` / `postgres`.

| Database / user / password | Used by |
|---|---|
| `oms_keycloak` | Keycloak |
| `oms_cart`, `oms_order`, `oms_pricing` | step 2 |
| `oms_saga` (order-orchestrator, the design's `saga_db`), `oms_inventory` (inventory-service), `oms_payment` | step 3 |
| `oms_orchestrator` | empty leftover from step 1 on old volumes (the saga uses `oms_saga`); new volumes no longer create it |

Add another one to a running container: `infra/postgres/create-service-db.sh <service>`.

Debezium login: `oms_debezium` / `oms_debezium` (REPLICATION, may connect to `oms_order` and read `outbox_event` only).
New volumes get it automatically; on a volume created before step 2 run `infra/postgres/create-debezium-user.sh` once.

## How to connect

**DBeaver** -> New connection -> PostgreSQL: host `localhost`, port `5434`, database `oms_order`
(or any above), user `oms_order`, password `oms_order`. For all databases use user `postgres` / `postgres`
with "Show all databases" ticked.

**Redis Insight** -> Add database -> host `localhost`, port `6379`, no username/password.
CLI: `docker exec -it oms-redis redis-cli`.

**Browser** -> http://localhost:8180 -> Administration Console -> `admin` / `admin` (master realm).
The application realm is `oms`: http://localhost:8180/admin/master/console/#/oms
OIDC discovery for the master realm: http://localhost:8180/realms/master/.well-known/openid-configuration

**Kafka UI** -> http://localhost:8090 -> cluster `oms-local`: Topics (messages are decoded with the Schema Registry,
so Avro events show as JSON), Schema Registry (subjects such as `oms.order.created-value`), Kafka Connect
(`debezium` -> connector status, restart). Consumer groups and lag are under "Consumers".

**Kafka CLI** (inside the container, no install needed):
```bash
docker exec -it oms-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list
docker exec -it oms-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic <topic> --from-beginning
```
Avro values print as binary there; use Kafka UI or `kafka-avro-console-consumer` in `oms-schema-registry`:
```bash
docker exec -it oms-schema-registry kafka-avro-console-consumer --bootstrap-server kafka:29092 \
  --topic oms.order.created --from-beginning --property schema.registry.url=http://localhost:8081
```

**Schema Registry REST**: `curl localhost:8081/subjects`, `curl localhost:8081/subjects/<subject>/versions/latest`.
Compatibility is `BACKWARD` (new schema must read data written with the previous one).

**Kafka Connect REST**: `curl localhost:8083/connectors?expand=status`, connector config under
`infra/debezium/`. Kafka Connect keeps its own state in the topics `_connect-configs`, `_connect-offsets`, `_connect-status`.

**Kafka data** is in the volume `oms_kafka-data` (`KAFKA_LOG_DIRS=/var/lib/kafka/data`). Before step 3 the setting was
missing, so Kafka wrote inside the container and every re-creation (`compose down`, image or config change) lost all
topics, the schemas and Connect's connector config. After that fix, run `scripts/register-order-outbox-connector.sh` once again.

**Elasticsearch**: no DBeaver/Compass needed. Browser or curl: `curl localhost:9200/_cat/indices?v`,
`curl 'localhost:9200/orders/_search?pretty'` (the alias `orders` points at index `orders-v1`),
`curl localhost:9200/orders/_doc/<orderId>?pretty`. In **Kibana** (`--profile kibana`): Management -> Dev Tools to run
the same queries, or Stack Management -> Data Views -> create a data view for `orders*` and use Discover.

**WireMock tax stub**: `curl -X POST localhost:8190/internal/v1/tax/calculate -H 'Content-Type: application/json' -d '{"zip":"10001","taxCode":"GENERAL"}'`.
Rates come from the files in `infra/wiremock/tax/mappings` (10001 NY 8.875%, 94105 CA 8.625%, 60601 IL 10.25%, 73301 TX 8.25%,
97201 OR 0%, anything else 7%; ZIP **99999 answers 503** to test the tax outage path). Edit a file and restart the container to change a rate.

**MongoDB Compass**: no MongoDB container yet (added with the services that need it).

## Keycloak realm `oms` (imported from `infra/keycloak/oms-realm.json`)

Test users (password for all: `Passw0rd!`, local only):

| User | Realm roles |
|---|---|
| `customer1` | CUSTOMER (via group `customers`) |
| `agent1` | SUPPORT_AGENT |
| `supervisor1` | SUPPORT_SUPERVISOR (composite: includes SUPPORT_AGENT) |
| `ops1` | OPS_ADMIN |
| `finance1` | FINANCE |

Clients: `mobile-app`, `web-app`, `admin-portal` (public, PKCE S256, no password grant);
`oms-dev-cli` (**dev only**, password grant, for curl); one confidential client per service
(`cart-service`, `order-service`, `pricing-service`, `order-orchestrator`, `inventory-service`, `payment-service`)
with secret `dev-<client>-secret` and the `SERVICE` role. Tokens carry `aud=oms-api`.

Get a token from the command line:
```bash
curl -s -d "client_id=oms-dev-cli&grant_type=password&username=agent1&password=Passw0rd!" \
  http://localhost:8180/realms/oms/protocol/openid-connect/token
```
Service token: `-d "client_id=order-service&client_secret=dev-order-service-secret&grant_type=client_credentials"`.

## Application services (run on the host, not in Docker yet)

| Service | App port | Management port (actuator) | URL |
|---|---|---|---|
| config-server | 8888 | 18888 | http://localhost:8888/{app}/{profile} |
| discovery-service | 8761 | 18761 | http://localhost:8761 (Eureka dashboard) |
| api-gateway | 8080 | 18080 | http://localhost:8080 |
| order-service | 8102 | 18102 | http://localhost:8102/swagger-ui.html (Swagger UI), `/v3/api-docs` |
| cart-service | 8101 | 18101 | http://localhost:8101/swagger-ui.html |
| pricing-service | 8103 | 18103 | http://localhost:8103/swagger-ui.html |
| order-query-service | 8104 | 18104 | http://localhost:8104/swagger-ui.html |

Start order: containers (`--profile foundation`), then config-server, discovery-service, api-gateway.
Check everything with `scripts/smoke-step1.sh` (22 checks; the rate-limit check needs ~60 s between runs).
Config Server serving from GitHub instead of the local folder: `SPRING_PROFILES_ACTIVE=git` (serves the `develop` branch).
Run each with `mvn -pl services/<name> spring-boot:run` or `java -jar services/<name>/target/<name>-0.1.0-SNAPSHOT.jar`
(from the service's own folder for config-server, which finds `config-repo/` by relative path).


## Step 2A: order-service and the outbox

```bash
docker compose --profile order-core up -d
mvn -DskipTests install
(cd services/config-server     && java -jar target/config-server-0.1.0-SNAPSHOT.jar &)
(cd services/discovery-service && java -jar target/discovery-service-0.1.0-SNAPSHOT.jar &)
(cd services/order-service     && java -jar target/order-service-0.1.0-SNAPSHOT.jar &)   # Flyway creates the schema
scripts/register-order-outbox-connector.sh     # once per Kafka Connect volume; safe to repeat
scripts/smoke-step2a.sh                        # 32 end-to-end checks
```

The connector must exist before orders are written that should be published (snapshot mode `no_data`).

**Swagger UI**: http://localhost:8102/swagger-ui.html -> "Authorize" -> paste an access token (no `Bearer ` prefix).
`/internal/**` needs a service token (role SERVICE):
```bash
curl -s -d "client_id=cart-service&client_secret=dev-cart-service-secret&grant_type=client_credentials" \
  http://localhost:8180/realms/oms/protocol/openid-connect/token | jq -r .access_token
```
`POST /internal/v1/orders` needs an `Idempotency-Key` header (8-100 characters).
api-gateway has no route to order-service: it is reached by the BFFs and cart-service (Part B), so call it directly on 8102.

**Database** (DBeaver): `oms_order` / `oms_order` on `localhost:5434`, database `oms_order`. Tables `orders`,
`order_lines`, `order_addresses`, `order_status_history`, `outbox_event` (payload is binary Avro; read events in Kafka UI).
Replication slot: `select * from pg_replication_slots;` as `postgres` (slot `oms_order_outbox`; `active=false` means
Kafka Connect is down and PostgreSQL is keeping WAL for it).

**Redis** (Redis Insight): idempotency keys `idem:http:<Idempotency-Key>`, TTL 24 h, value `DONE|<hash>|<orderId>`.

**Kafka topics** (Kafka UI -> Topics): `oms.order.created`, `oms.order.cancelled`, `oms.order.confirmed` (and other
`oms.order.<status>`), 3 partitions, key = order id, headers `id`, `eventType`, `aggregateType`, `correlationId`.
Schema subjects: `oms.order.created-value`, `oms.order.<status>-value`.

**Kafka Connect**: connector `order-outbox` (Kafka UI -> Kafka Connect -> debezium), or
`curl localhost:8083/connectors/order-outbox/status`. Restart a failed task:
`curl -X POST localhost:8083/connectors/order-outbox/tasks/0/restart`.


## Step 2B: cart, pricing and the order search

```bash
docker compose --profile order-core up -d       # now also Elasticsearch and the tax stub
mvn -DskipTests install
# start (each in its own terminal or with nohup): config-server, discovery-service, order-service,
# then scripts/register-order-outbox-connector.sh, then pricing-service, cart-service, order-query-service
scripts/smoke-step2b.sh                         # 48 end-to-end checks (takes about 10 s once everything is up)
```
The services find each other through Eureka by name (`pricing-service`, `order-service`); the first call after a start can
fail for up to ~30 s until Eureka's response cache shows the new instance. Tokens last 5 minutes.
If every call suddenly answers 401 "Jwt expired", check that the Docker VM clock matches the host
(`docker exec oms-keycloak date -u` against `date -u`); after the laptop sleeps it can lag for a while.

**Try it by hand** (Swagger UI of cart-service: http://localhost:8101/swagger-ui.html -> Authorize with a customer token):
```bash
T=$(curl -s -d "client_id=oms-dev-cli&grant_type=password&username=customer1&password=Passw0rd!" \
  http://localhost:8180/realms/oms/protocol/openid-connect/token | jq -r .access_token)
CART=$(curl -s -X POST localhost:8101/api/v1/carts -H "Authorization: Bearer $T" | jq -r .cartId)
curl -s -X POST localhost:8101/api/v1/carts/$CART/items -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"sku":"SKU-2","quantity":2}'
curl -s -X POST localhost:8101/api/v1/carts/$CART/checkout -H "Authorization: Bearer $T" -H "Idempotency-Key: try-$(date +%s)" \
  -H 'Content-Type: application/json' -d '{"shippingAddress":{"name":"Jane Doe","line1":"1 Main St","city":"New York","state":"NY","zip":"10001"},"couponCode":"SPRING10"}'
curl -s "localhost:8104/api/v1/order-query/orders?q=SKU-2" -H "Authorization: Bearer $T" | jq .
```
Sample data (Flyway seed in `oms_pricing`, mock catalog): SKU-1 19.99, SKU-2 49.50 (15% off automatically), SKU-3 5.00
(1.00 off each), SKU-4 129.00, SKU-5 0.99. Coupons: `SPRING10` (10% from 50), `WELCOME5` (5 off from 20), `OLD20` (expired).
Shipping 4.99, free from 75.00 after discounts.

**Databases** (DBeaver, host `localhost`, port `5434`): `oms_cart` / `oms_cart` (table `saved_cart`; active carts are NOT here, they
are in Redis), `oms_pricing` / `oms_pricing` (tables `product_price`, `promotion`). order-query-service has no SQL database.

**Redis** (Redis Insight, `localhost:6379`):

| Key | Owner | Meaning | TTL |
|---|---|---|---|
| `cart:{cartId}` | cart-service | hash: `json` (the cart), `v` (version for compare-and-set) | 7 days, sliding |
| `checkout:{customerId}:{Idempotency-Key}` | cart-service | frozen priced order request, then the created order | 24 h |
| `price:{sku}`, `promo:active` | pricing-service | cached base price / live promotions | 10 min / 5 min |
| `tax:{zip}:{taxCode}` | pricing-service | tax rate from the tax stub | 24 h |
| `evt:order-query:{eventId}` | order-query-service | event already applied (duplicate detection) | 7 days |
| `idem:http:{key}` | order-service | idempotency of order creation (step 2A) | 24 h |

**Elasticsearch / Kibana**: index `orders-v1` (strict mapping), alias `orders`. One document per order: `orderNumber`,
`status`, `customer.id`, `totals`, `lines[]`, `statusHistory[]`, `createdAt`. See the "Elasticsearch" paragraph above for how to look at it.

**Kafka UI**: consumer group `order-query-service` (Consumers) shows lag; the dead-letter topic is `oms.order-query.dlq`.
Its values are the **original bytes** (Confluent Avro, so Kafka UI shows them as binary unless you pick the SchemaRegistry
value serde); headers `kafka_dlt-original-topic/-partition/-offset` and `kafka_dlt-exception-*` say where it came from and why.

**Replaying dead letters** (`scripts/replay-dlq.sh`, needs only Kafka on localhost:9092 and a local Maven build):

```bash
scripts/replay-dlq.sh --dlq oms.order-query.dlq --list                    # partition:offset, key, original topic, reason
scripts/replay-dlq.sh --dlq oms.order-query.dlq --offset 0:5 --dry-run    # what would be sent where
scripts/replay-dlq.sh --dlq oms.order-query.dlq --offset 0:5              # or --key <orderId>, or --all
```

It sends the same key, bytes and headers back to `kafka_dlt-original-topic` (or `--to`), adds `oms-replayed-from`, and
leaves the DLQ unchanged. Replaying twice is harmless (idempotent consumer). Fix the cause first (registry up, ES up).

**Query API** (order-query-service, Swagger UI on 8104): `GET /api/v1/order-query/orders/{orderId}`, `GET /api/v1/order-query/orders?q=&status=&customerId=&page=&size=`.
Customers only see their own orders; staff (agent, supervisor, ops, finance, service) see all.
