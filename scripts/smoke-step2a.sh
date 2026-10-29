#!/bin/bash
# Smoke test for step 2 part A (order-service + transactional outbox), against the real local stack.
# Needs running: docker compose --profile order-core, config-server, discovery-service, order-service,
# and the connector (scripts/register-order-outbox-connector.sh). Prints PASS/FAIL, exits 1 if any failed.
KC=${KC:-http://localhost:8180}; OS=${OS:-http://localhost:8102}
TOKEN_URL="$KC/realms/oms/protocol/openid-connect/token"
fail=0

check() { # description expected actual
  if [ "$2" = "$3" ]; then printf "PASS  %-66s %s\n" "$1" "$3"; else printf "FAIL  %-66s expected %s, got %s\n" "$1" "$2" "$3"; fail=1; fi
}
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print(eval('d' + sys.argv[1]))" "$1"; }
token() { curl -s -d "client_id=oms-dev-cli&grant_type=password&username=$1&password=Passw0rd!" "$TOKEN_URL" | json "['access_token']"; }
sub() { python3 -c "import sys,json,base64; p=sys.argv[1].split('.')[1]; print(json.loads(base64.urlsafe_b64decode(p+'=='))['sub'])" "$1"; }
psql_order() { docker exec oms-postgres psql -U oms_order -d oms_order -Atc "$1"; }
# all values on a topic as JSON (Avro decoded with the Schema Registry), one per line
topic_dump() { docker exec oms-schema-registry kafka-avro-console-consumer --bootstrap-server kafka:29092 \
  --topic "$1" --from-beginning --timeout-ms 8000 --property schema.registry.url=http://localhost:8081 2>/dev/null; }

order_body() { cat <<EOF
{"customerId":"$1","channel":"WEB","currency":"USD",
 "lines":[{"sku":"SKU-SMOKE","quantity":2,"unitPrice":19.99,"discount":2.00,"tax":3.00,"fulfillmentType":"SHIP"}],
 "shippingAddress":{"name":"Smoke Test","line1":"1 Main St","city":"New York","state":"NY","zip":"10001"},
 "shippingTotal":4.99,"expectedGrandTotal":$2}
EOF
}

echo "== infrastructure and service"
check "order-service health (18102)"            200 "$(status http://localhost:18102/actuator/health)"
check "Swagger / OpenAPI docs"                  200 "$(status $OS/v3/api-docs)"
# Eureka serves /eureka/apps from a response cache refreshed every 30 s, so a fresh instance can take a while
for _ in $(seq 1 45); do reg=$(curl -s -H 'Accept: application/json' http://localhost:8761/eureka/apps | grep -c 'ORDER-SERVICE'); [ "$reg" -gt 0 ] && break; sleep 1; done
check "order-service registered in Eureka"      1 "$([ "$reg" -gt 0 ] && echo 1 || echo 0)"
check "Flyway V1 applied"                       "1|init|t" "$(psql_order "select version, description, success from flyway_schema_history")"
check "Debezium connector task RUNNING"         RUNNING "$(curl -s localhost:8083/connectors/order-outbox/status | json "['tasks'][0]['state']")"

echo "== tokens"
SVC=$(curl -s -d "client_id=cart-service&client_secret=dev-cart-service-secret&grant_type=client_credentials" "$TOKEN_URL" | json "['access_token']")
CUST=$(token customer1); AGENT=$(token agent1); FIN=$(token finance1)
[ -n "$SVC" ] && [ -n "$CUST" ] && [ -n "$AGENT" ] && [ -n "$FIN" ] || { echo "FAIL  could not get tokens from Keycloak"; exit 1; }
CUST_ID=$(sub "$CUST")

echo "== create order (idempotent)"
KEY="smoke-$(date +%s)-$RANDOM"; CORR="smoke-corr-$RANDOM"
resp=$(curl -s -D /tmp/oms-smoke-h1 -H "Authorization: Bearer $SVC" -H "Idempotency-Key: $KEY" -H "X-Correlation-Id: $CORR" \
  -H 'Content-Type: application/json' -d "$(order_body "$CUST_ID" 45.97)" $OS/internal/v1/orders)
ORDER_ID=$(echo "$resp" | json "['orderId']"); ORDER_NO=$(echo "$resp" | json "['orderNumber']")
check "POST /internal/v1/orders -> 201"         201 "$(awk 'NR==1{print $2}' /tmp/oms-smoke-h1)"
check "status PENDING, grand total 45.97"       "PENDING 45.97" "$(echo "$resp" | json "['status']") $(echo "$resp" | json "['grandTotal']")"
check "order number format"                     1 "$(echo "$ORDER_NO" | grep -cE '^OMS-[0-9]{8}-[0-9]{6}$')"
resp2=$(curl -s -D /tmp/oms-smoke-h2 -H "Authorization: Bearer $SVC" -H "Idempotency-Key: $KEY" \
  -H 'Content-Type: application/json' -d "$(order_body "$CUST_ID" 45.97)" $OS/internal/v1/orders)
check "retry, same key -> same order id"        "$ORDER_ID" "$(echo "$resp2" | json "['orderId']")"
check "retry flagged Idempotent-Replayed: true" true "$(tr -d '\r' < /tmp/oms-smoke-h2 | awk -F': ' 'tolower($1)=="idempotent-replayed"{print $2}')"
check "same key, different body -> 422 IDM-422" "IDM-422" "$(curl -s -H "Authorization: Bearer $SVC" -H "Idempotency-Key: $KEY" \
  -H 'Content-Type: application/json' -d "$(order_body "$CUST_ID" 45.97 | sed 's/"quantity":2/"quantity":3/')" $OS/internal/v1/orders | json "['errorCode']")"
check "no Idempotency-Key -> 400"               400 "$(status -H "Authorization: Bearer $SVC" -H 'Content-Type: application/json' -d "$(order_body "$CUST_ID" 45.97)" $OS/internal/v1/orders)"
ttl=$(docker exec oms-redis redis-cli ttl "idem:http:$KEY")
check "Redis key idem:http:<key> with ~24 h TTL" 1 "$([ "$ttl" -gt 86000 ] && [ "$ttl" -le 86400 ] && echo 1 || echo 0)"
check "exactly one order row for the key"       1 "$(psql_order "select count(*) from orders where idempotency_key = '$KEY'")"

echo "== security"
check "no token -> 401"                         401 "$(status $OS/api/v1/orders/$ORDER_ID)"
check "customer -> /internal -> 403"            403 "$(status -H "Authorization: Bearer $CUST" -H "Idempotency-Key: x-$KEY" -H 'Content-Type: application/json' -d "$(order_body "$CUST_ID" 45.97)" $OS/internal/v1/orders)"
check "customer reads own order -> 200"         200 "$(status -H "Authorization: Bearer $CUST" $OS/api/v1/orders/$ORDER_ID)"
check "agent reads any order -> 200"            200 "$(status -H "Authorization: Bearer $AGENT" $OS/api/v1/orders/$ORDER_ID)"
check "finance cannot cancel -> 403"            403 "$(status -H "Authorization: Bearer $FIN" -H 'Content-Type: application/json' -d '{"reason":"x"}' $OS/api/v1/orders/$ORDER_ID/cancel)"

echo "== cancel"
c=$(curl -s -H "Authorization: Bearer $CUST" -H 'Content-Type: application/json' -d '{"reason":"changed my mind"}' $OS/api/v1/orders/$ORDER_ID/cancel)
check "customer cancels own order -> CANCELLED" CANCELLED "$(echo "$c" | json "['status']")"
check "cancel again -> 200 (no-op)"             200 "$(status -H "Authorization: Bearer $CUST" -H 'Content-Type: application/json' -d '{"reason":"again"}' $OS/api/v1/orders/$ORDER_ID/cancel)"
check "status history rows (PENDING, CANCELLED)" "PENDING,CANCELLED" "$(psql_order "select string_agg(to_status, ',' order by changed_at, id) from order_status_history where order_id = '$ORDER_ID'")"
check "outbox rows for the order"               "order.created,order.cancelled" "$(psql_order "select string_agg(event_type, ',' order by created_at) from outbox_event where aggregate_id = '$ORDER_ID'")"

echo "== events in Kafka (via Debezium)"
created=$(topic_dump oms.order.created | grep -c "\"orderId\":\"$ORDER_ID\"")
check "oms.order.created has the order exactly once" 1 "$created"
check "event carries the order number"          1 "$(topic_dump oms.order.created | grep "\"orderId\":\"$ORDER_ID\"" | grep -c "\"orderNumber\":\"$ORDER_NO\"")"
check "oms.order.cancelled has the order exactly once" 1 "$(topic_dump oms.order.cancelled | grep -c "\"orderId\":\"$ORDER_ID\"")"
hdr=$(docker exec oms-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic oms.order.created \
  --from-beginning --timeout-ms 8000 --property print.headers=true --property print.key=true --property print.value=false 2>/dev/null | grep "$ORDER_ID")
check "record key = order id, header correlationId" 1 "$(echo "$hdr" | grep -c "correlationId:$CORR")"
subjects=$(curl -s localhost:8081/subjects)
check "schema subjects registered"              1 "$(echo "$subjects" | grep -q 'oms.order.created-value' && echo "$subjects" | grep -q 'oms.order.cancelled-value' && echo 1 || echo 0)"
check "topic has 3 partitions"                  3 "$(docker exec oms-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --describe --topic oms.order.created | grep -c 'Partition: ')"
check "Kafka UI sees the topic"                 1 "$(curl -s 'localhost:8090/api/clusters/oms-local/topics?search=oms.order' | grep -c '"name":"oms.order.created"')"

echo
[ $fail -eq 0 ] && echo "ALL CHECKS PASSED" || echo "SOME CHECKS FAILED"
exit $fail
