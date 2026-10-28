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

**Browser** -> http://localhost:8180 -> Administration Console -> `admin` / `admin`.
OIDC discovery for the master realm: http://localhost:8180/realms/master/.well-known/openid-configuration

**MongoDB Compass**: no MongoDB container yet (added with the services that need it).

Application services (run on the host with `mvn spring-boot:run`) are added to this file as they are built.
