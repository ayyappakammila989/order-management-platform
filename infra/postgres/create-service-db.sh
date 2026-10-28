#!/bin/bash
# Add one database + user (oms_<service>) to an already-running oms-postgres container.
# Usage: infra/postgres/create-service-db.sh <service-name>
set -euo pipefail
svc="${1:?usage: $0 <service-name>}"
[[ "$svc" =~ ^[a-z][a-z0-9_]*$ ]] || { echo "invalid name: $svc" >&2; exit 1; }
name="oms_${svc}"
docker exec -i oms-postgres psql -v ON_ERROR_STOP=1 -U postgres -d postgres <<SQL
CREATE USER ${name} WITH PASSWORD '${name}';
CREATE DATABASE ${name} OWNER ${name};
SQL
