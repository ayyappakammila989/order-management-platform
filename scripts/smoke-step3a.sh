#!/bin/bash
# Smoke test for step 3 part A against the real local stack: checkout (with the inventory pre-check) -> order-service
# -> Debezium -> oms.order.created -> order-orchestrator saga -> inventory-service reserve over Kafka -> order CONFIRMED,
# plus out-of-stock, a race the saga catches, customer cancel (release), inventory down, stock snapshot.
# Needs: docker compose --profile order-core, the connector, and scripts/services.sh start. Exits 1 on any FAIL.
# Mocked here: the stock snapshot producer (scripts/send-stock-snapshot.sh stands in for the SFTP/POS feeds).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KC=${KC:-http://localhost:8180}; CART=${CART:-http://localhost:8101}; ORD=${ORD:-http://localhost:8102}
INV=${INV:-http://localhost:8105}; SAGA=${SAGA:-http://localhost:8106}; ES=${ES:-http://localhost:9200}
TOKEN_URL="$KC/realms/oms/protocol/openid-connect/token"
fail=0
check() { if [ "$2" = "$3" ]; then printf "PASS  %-70s %s\n" "$1" "$3"; else printf "FAIL  %-70s expected %s, got %s\n" "$1" "$2" "$3"; fail=1; fi; }
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print(eval('d' + sys.argv[1]))" "$1"; }
token() { curl -s -d "client_id=oms-dev-cli&grant_type=password&username=$1&password=Passw0rd!" "$TOKEN_URL" | json "['access_token']"; }
psql_db() { docker exec oms-postgres psql -U "$1" -d "$1" -Atc "$2"; }
wait_for() { # description, expected, command that prints the value [, seconds]
  for _ in $(seq 1 "${4:-40}"); do v=$(eval "$3" 2>/dev/null); [ "$v" = "$2" ] && break; sleep 1; done; check "$1" "$2" "$v"; }
JSON='Content-Type: application/json'
# bodies in variables: escaped quotes inside "$( ... )" are mangled by the bash 3.2 that ships with macOS
ADDR='{"shippingAddress":{"name":"Jane Doe","line1":"1 Main St","city":"New York","state":"NY","zip":"10001"}}'
dlq_count() { docker exec oms-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:29092 --topic "$1" 2>/dev/null | awk -F: '{s+=$3} END{print s+0}'; }
reserved() { psql_db oms_inventory "select coalesce(sum(reserved),0) from stock where sku = '$1'"; }
order_status() { curl -s "$ORD/api/v1/orders/$1" -H "Authorization: Bearer $CUST" | json "['status']"; }
saga() { curl -s "$SAGA/internal/v1/sagas?orderId=$1" -H "Authorization: Bearer $AGENT"; }
saga_status() { saga "$1" | json "['status']"; }
trail() { saga "$1" | python3 -c "import sys,json; print(','.join(s['step']+' '+s['outcome'] for s in json.load(sys.stdin)['steps']))"; }
new_cart() { # sku quantity [sku quantity] -> cart id
  local c; c=$(curl -s -X POST $CART/api/v1/carts -H "Authorization: Bearer $CUST" | json "['cartId']")
  while [ $# -gt 0 ]; do
    curl -s -o /dev/null -X POST $CART/api/v1/carts/$c/items -H "Authorization: Bearer $CUST" -H "$JSON" -d "{\"sku\":\"$1\",\"quantity\":$2}"; shift 2
  done; echo "$c"; }
checkout() { # cart id, response file -> prints http status; body in the file
  curl -s -o "$2" -w '%{http_code}' -X POST $CART/api/v1/carts/$1/checkout -H "Authorization: Bearer $CUST" \
    -H "Idempotency-Key: smoke3a-$1" -H "$JSON" -d "$ADDR"; }
TMP=$(mktemp -d)
# cart-service finds inventory-service through Eureka (5 s registry fetch + 5 s load-balancer cache locally). Probe:
# 999 x SKU-5 must get 409 from the pre-check; a 201 means the pre-check was skipped (the saga then cancels the
# probe order for lack of stock, so even that is harmless) and we try again.
inventory_visible() {
  for _ in $(seq 1 10); do
    local c; c=$(new_cart SKU-5 999)
    [ "$(checkout $c $TMP/probe.json)" = 409 ] && { echo yes; return; }
    sleep 3
  done; echo no; }
skipped_for() { grep -c "Inventory pre-check skipped for cart $1" "$ROOT/.run/logs/cart-service.log"; }
INV_DLQ_BEFORE=$(dlq_count oms.inventory.dlq); SAGA_DLQ_BEFORE=$(dlq_count oms.order-orchestrator.dlq)

echo "== services"
for p in 18101:cart-service 18102:order-service 18105:inventory-service 18106:order-orchestrator; do
  check "${p#*:} health" UP "$(curl -s localhost:${p%%:*}/actuator/health | json "['status']")"; done
wait_for "inventory-service and order-orchestrator in Eureka" 2 \
  "curl -s -H 'Accept: application/json' http://localhost:8761/eureka/apps | grep -o '\"name\":\"\(INVENTORY-SERVICE\|ORDER-ORCHESTRATOR\)\"' | sort -u | wc -l | tr -d ' '" 45
check "Swagger of inventory / orchestrator" "200 200" "$(status $INV/v3/api-docs) $(status $SAGA/v3/api-docs)"
check "Debezium connector RUNNING"        RUNNING "$(curl -s localhost:8083/connectors/order-outbox/status | json "['tasks'][0]['state']")"

echo "== tokens"
CUST=$(token customer1); AGENT=$(token agent1)
[ -n "$CUST" ] && [ -n "$AGENT" ] || { echo "FAIL  no tokens"; exit 1; }

check "cart-service sees inventory-service (pre-check answers)" yes "$(inventory_visible)"

echo "== stock snapshot (mock SFTP/POS producer) and availability"
# exactly 2 units of SKU-5 free, whatever earlier runs left reserved: on hand = reserved + 2
SKU5_ON_HAND=$(( $(reserved SKU-5) + 2 ))
snap=$("$ROOT/scripts/send-stock-snapshot.sh" STORE-NYC SKU-5=$SKU5_ON_HAND | awk '{print $3}')
wait_for "snapshot applied for STORE-NYC"  "$snap" "psql_db oms_inventory \"select snapshot_id from location_snapshot where location_id = 'STORE-NYC'\"" 20
check "SKU-5 on hand at STORE-NYC"        "$SKU5_ON_HAND" "$(psql_db oms_inventory "select on_hand from stock where sku='SKU-5' and location_id='STORE-NYC'")"
check "availability without token -> 401" 401 "$(status $INV/api/v1/inventory/SKU-5/availability)"
SKU5_FREE=2
R5=$(reserved SKU-5)
check "customer sees SKU-5 available"      "$SKU5_FREE" "$(curl -s "$INV/api/v1/inventory/SKU-5/availability?zip=10001" -H "Authorization: Bearer $CUST" | json "['available']")"
check "availability cached atp:SKU-5:10001 (TTL <= 30 s)" 1 "$(t=$(docker exec oms-redis redis-cli ttl atp:SKU-5:10001 | tr -d '\r'); [ "$t" -gt 0 ] && [ "$t" -le 30 ] && echo 1 || echo 0)"

echo "== happy path: checkout -> saga -> CONFIRMED"
R1=$(reserved SKU-1); R2=$(reserved SKU-2)
C=$(new_cart SKU-1 2 SKU-2 1)
check "checkout -> 201"                   201 "$(checkout $C $TMP/ok.json)"
OK_ID=$(json "['orderId']" < $TMP/ok.json)
wait_for "order CONFIRMED by the saga (order-service)" CONFIRMED "order_status $OK_ID"
check "saga COMPLETED"                     COMPLETED "$(saga_status $OK_ID)"
check "saga step log" "SAGA STARTED,RESERVE_STOCK STARTED,RESERVE_STOCK SUCCEEDED,CONFIRM_ORDER STARTED,CONFIRM_ORDER SUCCEEDED,SAGA COMPLETED" "$(trail $OK_ID)"
check "reservation id stored in the saga"  1 "$(saga $OK_ID | json "['results']['reservationId']" | grep -c -- '-')"
wait_for "reservation ACTIVE, kept until shipment (oms.order.confirmed cleared the expiry)" "ACTIVE|" "psql_db oms_inventory \"select status||'|'||coalesce(expires_at::text,'') from reservation where order_id = '$OK_ID'\""
check "reserved from STORE-NYC (nearest to 10001) and a DC" "SKU-1@STORE-NYC:2,SKU-2@DC-EAST:1" "$(psql_db oms_inventory "select string_agg(sku||'@'||location_id||':'||quantity, ',' order by sku) from reservation_line l join reservation r using (reservation_id) where r.order_id = '$OK_ID'")"
check "stock reserved went up by 2 and 1" "$((R1+2)) $((R2+1))" "$(reserved SKU-1) $(reserved SKU-2)"
check "status history PENDING -> CONFIRMED by order-orchestrator" "CONFIRMED|order-orchestrator" "$(psql_db oms_order "select to_status||'|'||changed_by from order_status_history where order_id = '$OK_ID' and to_status = 'CONFIRMED'")"
wait_for "read model shows CONFIRMED (Elasticsearch)" CONFIRMED "curl -s $ES/orders/_doc/$OK_ID | json \"['_source']['status']\""

echo "== inventory pre-check at checkout"
C=$(new_cart SKU-5 3)
check "SKU-5 x3 (only $SKU5_FREE free) -> 409"     409 "$(checkout $C $TMP/short.json)"
check "error code CRT-409 with the shortage" "CRT-409|Not enough stock: SKU-5 (requested 3, available $SKU5_FREE)" "$(python3 -c "import json; d=json.load(open('$TMP/short.json')); print(d['errorCode']+'|'+d['message'])")"
check "cart kept for the customer to fix"  200 "$(status $CART/api/v1/carts/$C -H "Authorization: Bearer $CUST")"

echo "== race: two orders for the last 2 units, both pass the pre-check, the saga decides"
CA=$(new_cart SKU-5 2); CB=$(new_cart SKU-5 2)
(checkout $CA $TMP/a.json > $TMP/a.code) & (checkout $CB $TMP/b.json > $TMP/b.code) & wait
check "both checkouts -> 201"             "201 201" "$(cat $TMP/a.code) $(cat $TMP/b.code)"
check "the pre-check really ran for both (not skipped)" "0 0" "$(skipped_for $CA) $(skipped_for $CB)"
A_ID=$(json "['orderId']" < $TMP/a.json); B_ID=$(json "['orderId']" < $TMP/b.json)
for _ in $(seq 1 40); do sa=$(order_status $A_ID); sb=$(order_status $B_ID); [ "$sa" != PENDING ] && [ "$sb" != PENDING ] && break; sleep 1; done
check "one CONFIRMED, one CANCELLED"      "CANCELLED CONFIRMED" "$(printf '%s\n%s\n' "$sa" "$sb" | sort | tr '\n' ' ' | sed 's/ $//')"
LOSER=$A_ID; [ "$sa" = CONFIRMED ] && LOSER=$B_ID
check "loser's saga: reserve failed, aborted" "COMPENSATED|SAGA STARTED,RESERVE_STOCK STARTED,RESERVE_STOCK FAILED,SAGA ABORTED" "$(saga_status $LOSER)|$(trail $LOSER)"
check "cancel reason names the shortage"  1 "$(psql_db oms_order "select count(*) from order_status_history where order_id = '$LOSER' and to_status = 'CANCELLED' and reason like '%Not enough stock%SKU-5%'")"
check "SKU-5 reserved exactly 2 more (no oversell)" "$((R5+2))" "$(reserved SKU-5)"
WINNER=$A_ID; [ "$LOSER" = "$A_ID" ] && WINNER=$B_ID

echo "== customer cancels the confirmed order -> stock released"
check "cancel -> CANCELLED"               CANCELLED "$(curl -s -X POST $ORD/api/v1/orders/$OK_ID/cancel -H "Authorization: Bearer $CUST" -H "$JSON" -d '{"reason":"smoke 3a"}' | json "['status']")"
wait_for "reservation RELEASED (ORDER_CANCELLED)" "RELEASED|ORDER_CANCELLED" "psql_db oms_inventory \"select status||'|'||release_reason from reservation where order_id = '$OK_ID'\""
check "stock reserved back to before"      "$R1 $R2" "$(reserved SKU-1) $(reserved SKU-2)"
wait_for "saga notes the cancel after completion" 1 "trail $OK_ID | grep -c 'SAGA ABORTED'"
wait_for "read model shows CANCELLED"     CANCELLED "curl -s $ES/orders/_doc/$OK_ID | json \"['_source']['status']\""

echo "== inventory-service down: checkout still works, the saga waits and finishes later"
"$ROOT/scripts/services.sh" stop inventory-service >/dev/null
C=$(new_cart SKU-2 1)
check "checkout while inventory is down -> 201 (pre-check skipped)" 201 "$(checkout $C $TMP/down.json)"
DOWN_ID=$(json "['orderId']" < $TMP/down.json)
check "cart-service logged the skipped pre-check" 1 "$(skipped_for $C)"
sleep 5
check "order still PENDING, saga waits for RESERVE_STOCK" "PENDING RUNNING RESERVE_STOCK" "$(order_status $DOWN_ID) $(saga $DOWN_ID | json "['status']") $(saga $DOWN_ID | json "['currentStep']")"
"$ROOT/scripts/services.sh" start inventory-service >/dev/null
wait_for "after inventory is back: CONFIRMED" CONFIRMED "order_status $DOWN_ID" 60
check "saga COMPLETED"                     COMPLETED "$(saga_status $DOWN_ID)"
check "cart-service sees the restarted inventory-service again" yes "$(inventory_visible)"

echo "== messaging health"
check "no new dead letters (inventory, orchestrator)" "$INV_DLQ_BEFORE $SAGA_DLQ_BEFORE" "$(dlq_count oms.inventory.dlq) $(dlq_count oms.order-orchestrator.dlq)"
for g in inventory-service order-orchestrator; do
  out="$(docker exec oms-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --group $g 2>/dev/null)"
  check "consumer group $g exists and has no lag" "yes 0" "$(echo "$out" | grep -q ' oms\.' && echo yes || echo no) $(echo "$out" | awk 'NR>1 && $6 ~ /^[0-9]+$/ {s+=$6} END{print s+0}')"
done
check "outbox relays drained (inventory, saga)" "0 0" "$(psql_db oms_inventory "select count(*) from outbox_event where published_at is null") $(psql_db oms_saga "select count(*) from outbox_event where published_at is null")"
check "DLQ replay tool lists the inventory DLQ" 0 "$("$ROOT/scripts/replay-dlq.sh" --dlq oms.inventory.dlq --list >/dev/null 2>&1; echo $?)"

echo "== clean up: cancel what this run left confirmed, so the next run starts from the same free stock"
for o in $WINNER $DOWN_ID; do
  check "cancel order left by this run"     CANCELLED "$(curl -s -X POST $ORD/api/v1/orders/$o/cancel -H "Authorization: Bearer $CUST" -H "$JSON" -d '{"reason":"smoke 3a clean-up"}' | json "['status']")"
done
wait_for "their stock released"             "$R5" "reserved SKU-5"

rm -rf "$TMP"
echo
[ $fail -eq 0 ] && echo "ALL CHECKS PASSED" || echo "SOME CHECKS FAILED"
exit $fail
