# Local access

Everything here is **local development only** (default passwords, no TLS). Start only what you need:

```bash
docker compose --profile db up -d          # PostgreSQL
docker compose --profile cache up -d       # Redis
docker compose --profile auth up -d        # Keycloak (+ PostgreSQL)
docker compose --profile foundation up -d  # all of the above (step 1)
docker compose --profile foundation down   # stop (keeps data); `down -v` deletes data
```

## Containers

| Container | Profiles | Host port | URL | Login |
|---|---|---|---|---|
| `oms-postgres` (PostgreSQL 17) | db, auth, foundation | **5434** | `jdbc:postgresql://localhost:5434/<db>` | see below |
| `oms-redis` (Redis 7) | cache, foundation | 6379 | `redis://localhost:6379` | no password |
| `oms-keycloak` (Keycloak 26.8.1) | auth, foundation | **8180** (admin UI/OIDC), 9000 (management) | http://localhost:8180 | admin / admin |

Host ports 5432 and 5433 are used by other PostgreSQL instances, so Docker Postgres is on 5434.
Host 8080 is kept free for api-gateway, hence Keycloak on 8180.

### PostgreSQL databases (one database + one user per service)
Name and password are both `oms_<service>`; the superuser is `postgres` / `postgres`.

| Database / user / password | Used by |
|---|---|
| `oms_keycloak` | Keycloak |
| `oms_cart`, `oms_order`, `oms_pricing` | step 2 |
| `oms_orchestrator`, `oms_inventory`, `oms_payment` | step 3 |

Add another one to a running container: `infra/postgres/create-service-db.sh <service>`.

## How to connect

**DBeaver** -> New connection -> PostgreSQL: host `localhost`, port `5434`, database `oms_order`
(or any above), user `oms_order`, password `oms_order`. For all databases use user `postgres` / `postgres`
with "Show all databases" ticked.

**Redis Insight** -> Add database -> host `localhost`, port `6379`, no username/password.
CLI: `docker exec -it oms-redis redis-cli`.

**Browser** -> http://localhost:8180 -> Administration Console -> `admin` / `admin` (master realm).
The application realm is `oms`: http://localhost:8180/admin/master/console/#/oms
OIDC discovery for the master realm: http://localhost:8180/realms/master/.well-known/openid-configuration

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

Start order: containers (`--profile foundation`), then config-server, discovery-service, api-gateway.
Run each with `mvn -pl services/<name> spring-boot:run` or `java -jar services/<name>/target/<name>-0.1.0-SNAPSHOT.jar`
(from the service's own folder for config-server, which finds `config-repo/` by relative path).

