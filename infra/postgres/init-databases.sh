#!/bin/bash
# Runs once, on first start of an empty data volume (docker-entrypoint-initdb.d).
# One database + one login per service, named oms_<service> (a bare "order" is a reserved SQL word).
# Dev-only: password == user name.
# For services added later on an existing volume, run: infra/postgres/create-service-db.sh <name>
set -euo pipefail

SERVICES="keycloak cart order pricing saga inventory payment"

for svc in $SERVICES; do
  name="oms_${svc}"
  echo ">> creating database and user: $name"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<SQL
CREATE USER ${name} WITH PASSWORD '${name}';
CREATE DATABASE ${name} OWNER ${name};
SQL
done

# Debezium login (same SQL as create-debezium-user.sh, which does this on an existing volume)
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -f /docker-entrypoint-initdb.d/debezium-user.sql.inc
