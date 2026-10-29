#!/bin/bash
# Add the Debezium login (oms_debezium) to an already-running oms-postgres container.
# New volumes get it from init-databases.sh.
set -euo pipefail
cd "$(dirname "$0")"
docker exec -i oms-postgres psql -v ON_ERROR_STOP=1 -U postgres -d postgres < debezium-user.sql
