#!/usr/bin/env bash
# Personal learning project: start/stop the Spring Boot services on the host (the containers come from Compose).
#
#   scripts/services.sh start            # all services of the current step, in dependency order
#   scripts/services.sh start inventory-service order-orchestrator
#   scripts/services.sh stop [names]     # stop (all if no names)
#   scripts/services.sh status
#
# Jars from `mvn -DskipTests install`. Logs: .run/logs/<service>.log, pids: .run/<service>.pid (git-ignored).
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/.run"
mkdir -p "$RUN/logs"

# name:management-port, in start order (config-server and Eureka first)
ALL="config-server:18888 discovery-service:18761 order-service:18102 pricing-service:18103 cart-service:18101 \
order-query-service:18104 inventory-service:18105 order-orchestrator:18106"
JAVA_OPTS="${JAVA_OPTS:--Xms128m -Xmx384m}"
# config-server's default search location is relative (../../config-repo, from services/config-server); make it absolute
export OMS_CONFIG_REPO="${OMS_CONFIG_REPO:-$ROOT/config-repo}"

port_of() { for e in $ALL; do [ "${e%%:*}" = "$1" ] && echo "${e#*:}"; done; }
pid_of() { [ -f "$RUN/$1.pid" ] && kill -0 "$(cat "$RUN/$1.pid")" 2>/dev/null && cat "$RUN/$1.pid"; }
names() { if [ $# -gt 0 ]; then echo "$@"; else for e in $ALL; do printf '%s ' "${e%%:*}"; done; fi; }

start_one() {
  local name=$1 port jar
  port=$(port_of "$name"); [ -n "$port" ] || { echo "unknown service $name"; return 1; }
  if pid=$(pid_of "$name"); then echo "$name already running (pid $pid)"; return 0; fi
  jar=$(ls "$ROOT"/services/"$name"/target/"$name"-*.jar 2>/dev/null | grep -v -- '-stubs' | head -1)
  [ -n "$jar" ] || { echo "$name: no jar, run mvn -DskipTests install"; return 1; }
  # shellcheck disable=SC2086
  nohup java $JAVA_OPTS -jar "$jar" > "$RUN/logs/$name.log" 2>&1 &
  echo $! > "$RUN/$name.pid"
  printf '%-20s starting (pid %s) ' "$name" "$!"
  for _ in $(seq 1 90); do
    if curl -sf "localhost:$port/actuator/health" >/dev/null 2>&1; then echo "UP"; return 0; fi
    kill -0 "$(cat "$RUN/$name.pid")" 2>/dev/null || { echo "DIED, see .run/logs/$name.log"; return 1; }
    sleep 1
  done
  echo "not healthy after 90 s, see .run/logs/$name.log"; return 1
}

stop_one() {
  local name=$1
  if pid=$(pid_of "$name"); then
    kill "$pid"
    # graceful shutdown keeps answering for a moment: only return once the process is gone
    for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    if kill -0 "$pid" 2>/dev/null; then kill -9 "$pid"; echo "$name killed after 30 s (pid $pid)"; else echo "$name stopped (pid $pid)"; fi
  else
    echo "$name not running"
  fi
  rm -f "$RUN/$name.pid"
}

cmd=${1:-status}; shift || true
case "$cmd" in
  start)  for n in $(names "$@"); do start_one "$n" || exit 1; done ;;
  stop)   for n in $(names "$@"); do stop_one "$n"; done ;;
  status) for n in $(names "$@"); do
            if pid=$(pid_of "$n"); then
              printf '%-20s pid %-6s %s\n' "$n" "$pid" "$(curl -s "localhost:$(port_of "$n")/actuator/health" 2>/dev/null)"
            else printf '%-20s stopped\n' "$n"; fi; done ;;
  *) echo "usage: $0 start|stop|status [service ...]"; exit 2 ;;
esac
