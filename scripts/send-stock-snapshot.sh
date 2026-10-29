#!/usr/bin/env bash
# Personal learning project. MOCK of the warehouse SFTP feed / store POS (architecture 3.3 step 4): sends one
# StockSnapshotEvent (absolute on-hand counts for one location) to oms.inventory.snapshot. In step 9 the real
# file-exchange-service will turn SFTP CSV files into these events.
#
#   scripts/send-stock-snapshot.sh STORE-NYC SKU-5=3 SKU-1=12          # source SFTP, taken now
#   SOURCE=POS TAKEN_AT=2026-10-29T08:00:00Z scripts/send-stock-snapshot.sh STORE-SF SKU-3=4
#
# Needs the Compose kafka profile (oms-kafka, oms-schema-registry). Uses kafka-avro-console-producer inside the
# registry container, so nothing has to be installed on the host.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCATION="${1:?usage: $0 <locationId> SKU=count [SKU=count ...]}"
shift
[ $# -gt 0 ] || { echo "give at least one SKU=count" >&2; exit 1; }

items=""
for pair in "$@"; do
  [[ "$pair" =~ ^([A-Za-z0-9_-]+)=([0-9]+)$ ]] || { echo "not SKU=count: $pair" >&2; exit 1; }
  items="${items:+$items,}{\"sku\":\"${BASH_REMATCH[1]}\",\"onHand\":${BASH_REMATCH[2]}}"
done

if [ -n "${TAKEN_AT:-}" ]; then
  taken_ms=$(python3 -c "import datetime,sys; print(int(datetime.datetime.fromisoformat(sys.argv[1].replace('Z','+00:00')).timestamp()*1000))" "$TAKEN_AT")
else
  taken_ms=$(python3 -c "import time; print(int(time.time()*1000))")
fi
snapshot_id="$(uuidgen | tr 'A-Z' 'a-z')"
json="{\"snapshotId\":\"$snapshot_id\",\"takenAt\":$taken_ms,\"locationId\":\"$LOCATION\",\"source\":\"${SOURCE:-SFTP}\",\"items\":[$items]}"

docker cp "$ROOT/libs/oms-common-events/src/main/avro/inventory/StockSnapshotEvent.avsc" oms-schema-registry:/tmp/StockSnapshotEvent.avsc >/dev/null
printf '%s|%s\n' "$LOCATION" "$json" | docker exec -i oms-schema-registry kafka-avro-console-producer \
  --bootstrap-server kafka:29092 --topic oms.inventory.snapshot \
  --property schema.registry.url=http://localhost:8081 \
  --property value.schema.file=/tmp/StockSnapshotEvent.avsc \
  --property parse.key=true --property key.separator='|' \
  --property key.serializer=org.apache.kafka.common.serialization.StringSerializer >/dev/null 2>&1

echo "sent snapshot $snapshot_id for $LOCATION: $items"
