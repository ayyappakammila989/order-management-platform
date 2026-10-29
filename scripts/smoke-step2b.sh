#!/bin/bash
# Smoke test for step 2 part B, against the real local stack: cart -> pricing (+ tax stub) -> order -> Debezium ->
# Kafka -> order-query -> Elasticsearch. Needs: docker compose --profile order-core, config-server, discovery-service,
# order-service (+ connector), pricing-service, cart-service, order-query-service. Prints PASS/FAIL, exits 1 on failure.
KC=${KC:-http://localhost:8180}; CART=${CART:-http://localhost:8101}; PRICE=${PRICE:-http://localhost:8103}
QUERY=${QUERY:-http://localhost:8104}; ES=${ES:-http://localhost:9200}
TOKEN_URL="$KC/realms/oms/protocol/openid-connect/token"
fail=0
check() { if [ "$2" = "$3" ]; then printf "PASS  %-68s %s\n" "$1" "$3"; else printf "FAIL  %-68s expected %s, got %s\n" "$1" "$2" "$3"; fail=1; fi; }
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print(eval('d' + sys.argv[1]))" "$1"; }
token() { curl -s -d "client_id=oms-dev-cli&grant_type=password&username=$1&password=Passw0rd!" "$TOKEN_URL" | json "['access_token']"; }
sub() { python3 -c "import sys,json,base64; p=sys.argv[1].split('.')[1]; print(json.loads(base64.urlsafe_b64decode(p+'=='))['sub'])" "$1"; }
psql_order() { docker exec oms-postgres psql -U oms_order -d oms_order -Atc "$1"; }
JSON='Content-Type: application/json'
# bodies are built here, not inline: escaped quotes inside "$( ... )" are mangled by the bash 3.2 that ships with macOS
ADDR='{"name":"Jane Doe","line1":"1 Main St","city":"New York","state":"NY","zip":"10001"}'
dlq_count() { docker exec oms-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:29092 --topic oms.order-query.dlq 2>/dev/null | awk -F: '{s+=$3} END{print s+0}'; }
DLQ_BEFORE=$(dlq_count)      # earlier runs or old events may already be there; only growth during this run is a failure
BODY_NOKEY="{\"shippingAddress\":$ADDR}"
BODY_BADCOUPON="{\"shippingAddress\":$ADDR,\"couponCode\":\"NOPE\"}"
BODY_COUPON="{\"shippingAddress\":$ADDR,\"couponCode\":\"spring10\"}"
BODY_TAXDOWN='{"shippingAddress":{"name":"J","line1":"1","city":"X","state":"ZZ","zip":"99999"}}'
wait_for() { # description, command that prints the wanted value, expected
  for _ in $(seq 1 40); do v=$(eval "$2" 2>/dev/null); [ "$v" = "$3" ] && break; sleep 1; done; check "$1" "$3" "$v"; }

echo "== services"
for p in 18101:cart-service 18103:pricing-service 18104:order-query-service 18102:order-service; do
  check "${p#*:} health" UP "$(curl -s localhost:${p%%:*}/actuator/health | json "['status']")"; done
for _ in $(seq 1 45); do apps=$(curl -s -H 'Accept: application/json' http://localhost:8761/eureka/apps | grep -o '"name":"[A-Z-]*SERVICE"' | sort -u | tr -d '"' | tr '\n' ' '); echo "$apps" | grep -q 'CART' && echo "$apps" | grep -q 'PRICING' && echo "$apps" | grep -q 'ORDER-SERVICE' && break; sleep 1; done
check "cart, pricing and order registered in Eureka" 1 "$(echo "$apps" | grep -c 'CART-SERVICE.*ORDER-SERVICE.*PRICING-SERVICE\|CART-SERVICE.*PRICING-SERVICE.*ORDER-SERVICE')"
check "Swagger of cart / pricing / query"  "200 200 200" "$(status $CART/v3/api-docs) $(status $PRICE/v3/api-docs) $(status $QUERY/v3/api-docs)"
check "index orders-v1 with alias orders" orders-v1 "$(curl -s $ES/_alias/orders | python3 -c 'import sys,json; print(list(json.load(sys.stdin))[0])')"
check "tax stub answers for 10001"       0.08875 "$(curl -s -X POST localhost:8190/internal/v1/tax/calculate -H "$JSON" -d '{"zip":"10001","taxCode":"GENERAL"}' | json "['rate']")"

echo "== tokens"
CUST=$(token customer1); AGENT=$(token agent1)
SVC=$(curl -s -d "client_id=cart-service&client_secret=dev-cart-service-secret&grant_type=client_credentials" "$TOKEN_URL" | json "['access_token']")
[ -n "$CUST" ] && [ -n "$AGENT" ] && [ -n "$SVC" ] || { echo "FAIL  no tokens"; exit 1; }
CUST_ID=$(sub "$CUST"); AGENT_ID=$(sub "$AGENT")

echo "== pricing-service"
Q='{"currency":"USD","shipToZip":"10001","couponCode":"SPRING10","lines":[{"sku":"SKU-1","quantity":2},{"sku":"SKU-2","quantity":1}]}'
check "quote without token -> 401"        401 "$(status -X POST $PRICE/internal/v1/prices/quote -H "$JSON" -d "$Q")"
check "quote as customer -> 403"          403 "$(status -X POST $PRICE/internal/v1/prices/quote -H "Authorization: Bearer $CUST" -H "$JSON" -d "$Q")"
quote=$(curl -s -X POST $PRICE/internal/v1/prices/quote -H "Authorization: Bearer $SVC" -H "$JSON" -d "$Q")
check "quote: discount 15.64, tax 6.55, shipping 4.99, grand 85.38" "15.64 6.55 4.99 85.38" \
  "$(echo "$quote" | json "['discountTotal']") $(echo "$quote" | json "['taxTotal']") $(echo "$quote" | json "['shippingTotal']") $(echo "$quote" | json "['grandTotal']")"
check "unknown SKU -> 404 PRC-404"        PRC-404 "$(curl -s -X POST $PRICE/internal/v1/prices/quote -H "Authorization: Bearer $SVC" -H "$JSON" -d '{"currency":"USD","shipToZip":"10001","lines":[{"sku":"NOPE","quantity":1}]}' | json "['errorCode']")"
check "expired coupon OLD20 -> 422"       422 "$(status -X POST $PRICE/internal/v1/prices/quote -H "Authorization: Bearer $SVC" -H "$JSON" -d '{"currency":"USD","shipToZip":"10001","couponCode":"OLD20","lines":[{"sku":"SKU-1","quantity":1}]}')"
check "tax provider down (ZIP 99999) -> 503 PRC-503" PRC-503 "$(curl -s -X POST $PRICE/internal/v1/prices/quote -H "Authorization: Bearer $SVC" -H "$JSON" -d '{"currency":"USD","shipToZip":"99999","lines":[{"sku":"SKU-1","quantity":1}]}' | json "['errorCode']")"
check "tax rate cached in Redis (TTL <= 24 h)" 1 "$(t=$(docker exec oms-redis redis-cli ttl tax:10001:GENERAL | tr -d '\r'); [ "$t" -gt 3600 ] && [ "$t" -le 86400 ] && echo 1 || echo 0)"
check "price and promo caches in Redis"   "1 1" "$(docker exec oms-redis redis-cli exists price:SKU-1 | tr -d '\r') $(docker exec oms-redis redis-cli exists promo:active | tr -d '\r')"
check "coupon validate (customer)"        "True" "$(curl -s -X POST $PRICE/api/v1/coupons/validate -H "Authorization: Bearer $CUST" -H "$JSON" -d '{"couponCode":"WELCOME5","subtotal":30.00}' | json "['valid']")"

echo "== cart"
check "create cart without token -> 401"  401 "$(status -X POST $CART/api/v1/carts)"
cart=$(curl -s -X POST $CART/api/v1/carts -H "Authorization: Bearer $CUST"); CART_ID=$(echo "$cart" | json "['cartId']")
check "cart created for the token's customer" "$CUST_ID" "$(echo "$cart" | json "['customerId']")"
check "cart in Redis with ~7 days TTL"    1 "$(t=$(docker exec oms-redis redis-cli ttl cart:$CART_ID); [ "$t" -gt 604000 ] && echo 1 || echo 0)"
curl -s -X POST $CART/api/v1/carts/$CART_ID/items -H "Authorization: Bearer $CUST" -H "$JSON" -d '{"sku":"SKU-1","quantity":1}' >/dev/null
curl -s -X POST $CART/api/v1/carts/$CART_ID/items -H "Authorization: Bearer $CUST" -H "$JSON" -d '{"sku":"SKU-1","quantity":1}' >/dev/null
c=$(curl -s -X POST $CART/api/v1/carts/$CART_ID/items -H "Authorization: Bearer $CUST" -H "$JSON" -d '{"sku":"SKU-2","quantity":1}')
check "SKU-1 added twice -> one line of 2, plus SKU-2" "SKU-1:2 SKU-2:1" "$(echo "$c" | python3 -c "import sys,json; print(' '.join(i['sku']+':'+str(i['quantity']) for i in json.load(sys.stdin)['items']))")"
check "another customer cannot read the cart -> 404" 404 "$(status -H "Authorization: Bearer $AGENT" $CART/api/v1/carts/$CART_ID)"
check "bad quantity -> 400"               400 "$(status -X POST $CART/api/v1/carts/$CART_ID/items -H "Authorization: Bearer $CUST" -H "$JSON" -d '{"sku":"SKU-1","quantity":0}')"

echo "== checkout"
check "no Idempotency-Key -> 400"         400 "$(status -X POST $CART/api/v1/carts/$CART_ID/checkout -H "Authorization: Bearer $CUST" -H "$JSON" -d "$BODY_NOKEY")"
check "unknown coupon -> 422, cart kept"  422 "$(status -X POST $CART/api/v1/carts/$CART_ID/checkout -H "Authorization: Bearer $CUST" -H "Idempotency-Key: smoke-badcoupon-$RANDOM" -H "$JSON" -d "$BODY_BADCOUPON")"
check "tax outage (ZIP 99999) -> 503, cart kept" 503 "$(status -X POST $CART/api/v1/carts/$CART_ID/checkout -H "Authorization: Bearer $CUST" -H "Idempotency-Key: smoke-taxdown-$RANDOM" -H "$JSON" -d "$BODY_TAXDOWN")"
check "cart still in Redis after failures" 1 "$(docker exec oms-redis redis-cli exists cart:$CART_ID | tr -d '\r')"
KEY="smoke-checkout-$(date +%s)-$RANDOM"
resp=$(curl -s -D /tmp/oms-smoke-b1 -X POST $CART/api/v1/carts/$CART_ID/checkout -H "Authorization: Bearer $CUST" -H "Idempotency-Key: $KEY" -H "X-Correlation-Id: smoke-b-$RANDOM" -H "$JSON" -d "$BODY_COUPON")
ORDER_ID=$(echo "$resp" | json "['orderId']"); ORDER_NO=$(echo "$resp" | json "['orderNumber']")
check "checkout -> 201"                   201 "$(awk 'NR==1{print $2}' /tmp/oms-smoke-b1)"
check "order PENDING, grand total 85.38"  "PENDING 85.38" "$(echo "$resp" | json "['status']") $(echo "$resp" | json "['grandTotal']")"
check "cart deleted from Redis"           0 "$(docker exec oms-redis redis-cli exists cart:$CART_ID | tr -d '\r')"
resp2=$(curl -s -D /tmp/oms-smoke-b2 -X POST $CART/api/v1/carts/$CART_ID/checkout -H "Authorization: Bearer $CUST" -H "Idempotency-Key: $KEY" -H "$JSON" -d "$BODY_COUPON")
check "retry with the same key (cart is gone) -> same order" "$ORDER_ID" "$(echo "$resp2" | json "['orderId']")"
check "retry flagged replayed"            true "$(tr -d '\r' < /tmp/oms-smoke-b2 | awk -F': ' 'tolower($1)=="idempotent-replayed"{print $2}')"

echo "== order database (order-service)"
check "one orders row for the key"        1 "$(psql_order "select count(*) from orders where idempotency_key = '$KEY'")"
check "row: customer, PENDING, totals"    "$CUST_ID|PENDING|89.48|15.64|6.55|4.99|85.38" "$(psql_order "select customer_id, status, subtotal, discount_total, tax_total, shipping_total, grand_total from orders where order_id = '$ORDER_ID'")"
check "two lines with line discounts/tax" "SKU-1,2,4.00,3.19;SKU-2,1,11.64,3.36" "$(psql_order "select string_agg(sku||','||quantity||','||discount||','||tax, ';' order by sku) from order_lines where order_id = '$ORDER_ID'")"

echo "== read model (order-query-service -> Elasticsearch)"
wait_for "order appears in Elasticsearch (PENDING)" "curl -s $ES/orders/_doc/$ORDER_ID | json \"['_source']['status']\"" PENDING
doc=$(curl -s $ES/orders/_doc/$ORDER_ID)
check "ES doc: order number, customer, total" "$ORDER_NO $CUST_ID 85.38" "$(echo "$doc" | json "['_source']['orderNumber']") $(echo "$doc" | json "['_source']['customer']['id']") $(echo "$doc" | json "['_source']['totals']['grandTotal']")"
check "ES doc: 2 lines, zip 10001"        "2 10001" "$(echo "$doc" | python3 -c "import sys,json; s=json.load(sys.stdin)['_source']; print(len(s['lines']), s['shipToZip'])")"
check "event id remembered in Redis (7 d)" 1 "$(n=$(docker exec oms-redis redis-cli --scan --pattern 'evt:order-query:*' | wc -l); [ "$n" -ge 1 ] && echo 1 || echo 0)"
check "query API: customer finds it by id" "$ORDER_NO" "$(curl -s -H "Authorization: Bearer $CUST" $QUERY/api/v1/order-query/orders/$ORDER_ID | json "['orderNumber']")"
wait_for "query API: search by order number" "curl -s -H 'Authorization: Bearer $CUST' '$QUERY/api/v1/order-query/orders?q=$ORDER_NO' | json \"['total']\"" 1
check "query API: unknown order id -> 404" 404 "$(status -H "Authorization: Bearer $CUST" $QUERY/api/v1/order-query/orders/does-not-exist)"
check "query API: agent reads it"         200 "$(status -H "Authorization: Bearer $AGENT" $QUERY/api/v1/order-query/orders/$ORDER_ID)"

echo "== cancel flows through to the index"
SVC_ORDER=http://localhost:8102
check "customer cancels -> CANCELLED"     CANCELLED "$(curl -s -H "Authorization: Bearer $CUST" -H "$JSON" -d '{"reason":"smoke test"}' $SVC_ORDER/api/v1/orders/$ORDER_ID/cancel | json "['status']")"
wait_for "Elasticsearch shows CANCELLED"  "curl -s $ES/orders/_doc/$ORDER_ID | json \"['_source']['status']\"" CANCELLED
check "status history has CANCELLED and PENDING" "CANCELLED,PENDING" "$(curl -s $ES/orders/_doc/$ORDER_ID | python3 -c "import sys,json; print(','.join(sorted(h['to'] for h in json.load(sys.stdin)['_source']['statusHistory'])))")"
check "nothing new in the dead-letter topic" "$DLQ_BEFORE" "$(dlq_count)"
GROUP_OUT="$(docker exec oms-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --group order-query-service 2>/dev/null)"
# (until the step 3 follow-up the listener id was used as group id, so this group did not exist and "no lag" passed vacuously)
check "consumer group order-query-service reads oms.order.created" yes "$(echo "$GROUP_OUT" | grep -q ' oms.order.created ' && echo yes || echo no)"
check "consumer group has no lag"         0 "$(echo "$GROUP_OUT" | awk 'NR>1 && $6 ~ /^[0-9]+$/ {s+=$6} END{print s+0}')"

echo
[ $fail -eq 0 ] && echo "ALL CHECKS PASSED" || echo "SOME CHECKS FAILED"
exit $fail
