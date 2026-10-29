#!/bin/bash
# Creates or updates the Debezium outbox connector for order-service (idempotent: PUT .../config).
# Needs: docker compose --profile order-core up -d, and order-service started once (Flyway creates the
# outbox table and the publication the connector reads).
set -euo pipefail
CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"
FILE="$(dirname "$0")/../infra/debezium/order-outbox-connector.json"
NAME="$(jq -r .name "$FILE")"

jq .config "$FILE" | curl -sS -f -X PUT -H "Content-Type: application/json" --data @- \
  "$CONNECT_URL/connectors/$NAME/config" >/dev/null
echo "registered $NAME, waiting for RUNNING..."
for _ in $(seq 1 30); do
  status="$(curl -sS "$CONNECT_URL/connectors/$NAME/status")"
  connector="$(jq -r .connector.state <<<"$status")"
  task="$(jq -r '.tasks[0].state // "NONE"' <<<"$status")"
  if [[ "$connector" == "RUNNING" && "$task" == "RUNNING" ]]; then
    echo "$NAME connector=$connector task=$task"
    exit 0
  fi
  if [[ "$task" == "FAILED" ]]; then
    jq -r '.tasks[0].trace' <<<"$status" | head -20
    exit 1
  fi
  sleep 1
done
echo "timed out: $status"
exit 1
