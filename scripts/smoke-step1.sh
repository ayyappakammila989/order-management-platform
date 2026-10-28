#!/bin/bash
# Smoke test for step 1 (foundation). Needs running: docker compose --profile foundation,
# config-server, discovery-service, api-gateway. Prints PASS/FAIL per check, exits 1 if any failed.
# The rate-limit check uses up supervisor1's chat bucket; wait ~60 s before running it again.
KC=${KC:-http://localhost:8180}; GW=${GW:-http://localhost:8080}
TOKEN_URL="$KC/realms/oms/protocol/openid-connect/token"
fail=0

check() { # description expected actual
  if [ "$2" = "$3" ]; then printf "PASS  %-62s %s\n" "$1" "$3"; else printf "FAIL  %-62s expected %s, got %s\n" "$1" "$2" "$3"; fail=1; fi
}
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
token() { curl -s -d "client_id=oms-dev-cli&grant_type=password&username=$1&password=Passw0rd!" "$TOKEN_URL" \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('access_token',''))"; }
bearer() { status -H "Authorization: Bearer $1" "$GW$2"; }

echo "== infrastructure and services"
check "config-server health (18888)"      200 "$(status http://localhost:18888/actuator/health)"
check "discovery-service health (18761)"  200 "$(status http://localhost:18761/actuator/health)"
check "api-gateway health (18080)"        200 "$(status http://localhost:18080/actuator/health)"
check "keycloak realm oms reachable"      200 "$(status $KC/realms/oms/.well-known/openid-configuration)"
check "redis answers PONG"                PONG "$(docker exec oms-redis redis-cli ping 2>/dev/null)"
check "config-server serves api-gateway config" 200 "$(status http://localhost:8888/api-gateway/default)"
reg=$(curl -s -H 'Accept: application/json' http://localhost:8761/eureka/apps | grep -c 'API-GATEWAY')
check "api-gateway registered in Eureka"  1 "$([ "$reg" -gt 0 ] && echo 1 || echo 0)"

echo "== authentication through the gateway"
CUST=$(token customer1); AGENT=$(token agent1); SUP=$(token supervisor1)
[ -n "$CUST" ] && [ -n "$AGENT" ] && [ -n "$SUP" ] || { echo "FAIL  could not get tokens from Keycloak"; exit 1; }
MASTER=$(curl -s -d "client_id=admin-cli&grant_type=password&username=admin&password=admin" $KC/realms/master/protocol/openid-connect/token | python3 -c "import sys,json; print(json.load(sys.stdin).get('access_token',''))")
check "no token -> 401"                              401 "$(status $GW/api/v1/web/orders)"
check "tampered token -> 401"                        401 "$(bearer "${AGENT}x" /api/v1/web/orders)"
check "token from another realm (wrong issuer) -> 401" 401 "$(bearer "$MASTER" /api/v1/web/orders)"
check "customer, web route, BFF down -> 503 fallback" 503 "$(bearer "$CUST" /api/v1/web/orders)"

echo "== authorization by role"
check "customer -> admin route -> 403"               403 "$(bearer "$CUST" /api/v1/admin/orders)"
check "agent -> admin route (allowed, BFF down) -> 503" 503 "$(bearer "$AGENT" /api/v1/admin/orders)"
check "customer -> chat -> 403"                      403 "$(bearer "$CUST" /api/v1/chat/ask)"
check "agent -> chat (allowed, service down) -> 503"  503 "$(bearer "$AGENT" /api/v1/chat/ask)"

echo "== public paths and blocked paths"
check "webhook without token (public, service down) -> 503" 503 "$(status $GW/webhooks/stripe)"
check "store locator without token (public) -> 503"  503 "$(status $GW/api/v1/stores/1)"
check "/internal/** -> 403 even for a supervisor"     403 "$(bearer "$SUP" /internal/orders)"

echo "== standard error body and correlation id"
body=$(curl -s -H 'X-Correlation-Id: smoke-123' $GW/api/v1/web/orders)
check "error body has errorCode SEC-401"  SEC-401 "$(echo "$body" | python3 -c 'import sys,json; print(json.load(sys.stdin)["errorCode"])')"
check "error body traceId = X-Correlation-Id" smoke-123 "$(echo "$body" | python3 -c 'import sys,json; print(json.load(sys.stdin)["traceId"])')"
check "response echoes X-Correlation-Id" smoke-123 "$(curl -s -D - -o /dev/null -H 'X-Correlation-Id: smoke-123' $GW/api/v1/web/orders | tr -d '\r' | awk -F': ' 'tolower($1)=="x-correlation-id"{print $2}')"

echo "== rate limit (chat: 10 requests/minute per user)"
codes=""; for i in $(seq 1 11); do codes="$codes $(bearer "$SUP" /api/v1/chat/ask)"; done
check "10 allowed (503 = service down), 11th -> 429" "503 503 503 503 503 503 503 503 503 503 429" "$(echo $codes)"

echo
[ $fail -eq 0 ] && echo "ALL CHECKS PASSED" || echo "SOME CHECKS FAILED"
exit $fail
