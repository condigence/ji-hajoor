#!/usr/bin/env bash
# Ji-Hajoor load tests. Runs k6 (in Docker, no install needed) against the stack that
# deploy/ci/run-stack.sh starts: the Railway layout, with the fixed OTP code, the payment
# simulator and the seeded catalog. See loadtest/README.md.
#
#   bash loadtest/run.sh stack-up            # build jh/* images from the built jars, start + seed
#   bash loadtest/run.sh marketplace         # PROFILE=smoke|load|stress (default load)
#   bash loadtest/run.sh guardrails          # rate-limit and OTP-throttle checks
#   bash loadtest/run.sh stats               # container CPU / memory now
#   bash loadtest/run.sh stack-down          # remove the containers and the network
#
# Build the jars first (JDK 25): mvn -q -DskipTests -Djacoco.skip=true package
set -euo pipefail
cd "$(dirname "$0")/.."

K6_IMAGE="${K6_IMAGE:-grafana/k6:1.0.0}"
NET=jh
DEPLOYABLES="identity-app jobs-app money-app engagement-app api-gateway"
CONTAINERS="$DEPLOYABLES admin-portal customer-app provider-app postgres redis kafka seed seed-test-1 seed-test-2"

stack_up() {
  local dir=loadtest/.images
  mkdir -p "$dir"
  for a in identity-app jobs-app money-app engagement-app; do cp "apps/$a/target/$a-1.0.0-SNAPSHOT.jar" "$dir/$a.jar"; done
  cp services/api-gateway/target/api-gateway-1.0.0-SNAPSHOT.jar "$dir/api-gateway.jar"
  # Same runtime image as .github/workflows/booking-flow.yml (and so as Railway).
  cat > "$dir/Dockerfile" <<'EOF'
FROM eclipse-temurin:25-jre-alpine AS extract
ARG JAR
COPY loadtest/.images/${JAR} /app.jar
RUN cd / && java -Djarmode=tools -jar /app.jar extract --destination /extracted
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S -u 999 -G app app
WORKDIR /app
COPY --from=extract /extracted/lib/ lib/
COPY --from=extract /extracted/app.jar app.jar
COPY deploy/entrypoint.sh /app/entrypoint.sh
RUN chmod 755 /app/entrypoint.sh
USER app
ENTRYPOINT ["/app/entrypoint.sh"]
EOF
  for a in $DEPLOYABLES; do
    echo "image jh/$a"; docker build -q -f "$dir/Dockerfile" --build-arg JAR="$a.jar" -t "jh/$a" . >/dev/null
  done
  docker build -q -t jh/seed deploy/seed >/dev/null
  for w in admin-portal customer-app provider-app; do
    echo "image jh/$w"; docker build -q -t "jh/$w" "frontend/$w" >/dev/null
  done
  bash deploy/ci/run-stack.sh
}

stack_down() {
  # shellcheck disable=SC2086
  docker rm -f $CONTAINERS >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  echo "stack removed"
}

k6() {
  local script="$1"; shift
  mkdir -p loadtest/results
  # MSYS_NO_PATHCONV keeps Git Bash on Windows from rewriting the container paths.
  MSYS_NO_PATHCONV=1 docker run --rm -i --network "$NET" \
    -v "$(pwd)/loadtest/k6:/scripts:ro" -v "$(pwd)/loadtest/results:/results" \
    -e GATEWAY=http://api-gateway:8080 -e PROFILE="${PROFILE:-load}" -e SUMMARY_DIR=/results \
    -e BROWSE_VUS -e CUSTOMER_VUS -e PROVIDERS -e HOLD -e RUN_ID -e OTP -e ADMIN_USER -e ADMIN_PASS \
    "$K6_IMAGE" run "$@" "/scripts/$script"
}

case "${1:-}" in
  stack-up) stack_up ;;
  stack-down) stack_down ;;
  marketplace) shift; k6 marketplace.js "$@" ;;
  guardrails) shift; k6 guardrails.js "$@" ;;
  stats) docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}' $DEPLOYABLES postgres redis kafka ;;
  *) sed -n '2,13p' "$0"; exit 2 ;;
esac
