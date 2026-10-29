#!/usr/bin/env bash
# Personal learning project: puts dead-lettered Kafka records back on the topic they came from.
#
#   scripts/replay-dlq.sh --dlq oms.order-query.dlq --list                 # what is in the DLQ (partition:offset, key, reason)
#   scripts/replay-dlq.sh --dlq oms.order-query.dlq --offset 0:5 --dry-run # show what would be replayed
#   scripts/replay-dlq.sh --dlq oms.order-query.dlq --offset 0:5           # replay one record (repeat --offset for more)
#   scripts/replay-dlq.sh --dlq oms.order-query.dlq --key <orderId>        # replay every record with this key
#   scripts/replay-dlq.sh --dlq oms.order-query.dlq --all                  # replay everything
#
# Same key, same value bytes, original headers (minus kafka_dlt-*), plus header oms-replayed-from=<dlq>-<p>@<offset>.
# The target is the kafka_dlt-original-topic header unless --to is given. Default broker: localhost:9092 (Compose).
# Replaying twice is safe (the consumers are idempotent). The DLQ is not changed.
# Code: libs/oms-common-kafka/src/main/java/com/retail/oms/kafka/DlqReplay.java
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIB="$ROOT/libs/oms-common-kafka"
CLASSES="$LIB/target/classes"
CP_FILE="$LIB/target/replay-classpath.txt"

if [ ! -f "$CLASSES/com/retail/oms/kafka/DlqReplay.class" ] || [ ! -f "$CP_FILE" ] \
    || [ "$LIB/src/main/java/com/retail/oms/kafka/DlqReplay.java" -nt "$CLASSES/com/retail/oms/kafka/DlqReplay.class" ]; then
  echo "Building libs/oms-common-kafka ..." >&2
  (cd "$ROOT" && mvn -q -o -pl libs/oms-common-kafka -am install -DskipTests >&2)
  (cd "$ROOT" && mvn -q -o -pl libs/oms-common-kafka dependency:build-classpath \
      -Dmdep.outputFile="$CP_FILE" -Dmdep.includeScope=runtime >&2)
fi

args=("$@")
case " $* " in
  *" --bootstrap "*) ;;
  *) args=(--bootstrap "${KAFKA_BOOTSTRAP:-localhost:9092}" "${args[@]+"${args[@]}"}") ;;
esac

exec java -Dlogback.configurationFile="$ROOT/scripts/replay-dlq.logback.xml" -cp "$CLASSES:$(cat "$CP_FILE")" com.retail.oms.kafka.DlqReplay "${args[@]}"
