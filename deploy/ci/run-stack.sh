#!/usr/bin/env bash
# Starts the whole Ji-Hajoor stack in Docker the way it runs on Railway: one
# Postgres, password-protected Redis, single-node Kafka, the five backend
# deployables (four multi-service apps + gateway) configured only through
# DATABASE_URL / REDIS_URL / KAFKA_BOOTSTRAP_SERVERS plus the same variables
# used on Railway, and the three web apps. Images are expected as jh/<name>.
# Used by the "Booking flow" workflow.
set -euo pipefail

NET=jh
docker network create "$NET" >/dev/null 2>&1 || true

DB='postgresql://postgres:pg-p%40ss@postgres:5432/railway'
REDIS='redis://default:redis-secret@redis:6379'
KAFKA='kafka:9092'
JWT="$(openssl rand -hex 32)"
KEY='ci-internal-api-key'
KMS='MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY='

docker run -d --name postgres --network "$NET" -e POSTGRES_PASSWORD='pg-p@ss' -e POSTGRES_DB=railway postgres:16-alpine
docker run -d --name redis --network "$NET" redis:7-alpine redis-server --requirepass redis-secret
docker run -d --name kafka --network "$NET" \
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=true \
  -e KAFKA_HEAP_OPTS='-Xmx512m -Xms256m' apache/kafka:3.8.0

for i in $(seq 1 30); do docker exec postgres pg_isready -U postgres -d railway >/dev/null 2>&1 && break; sleep 2; done

# Report a container's error lines as a GitHub annotation (job logs are not always readable).
annotate_logs() {
  # Logs are JSON lines: decode message + stack trace so the cause is not cut off.
  local body
  body="$(docker logs "$1" 2>&1 | grep '"logLevel":"ERROR"' | tail -3 \
    | jq -r '(.message // "") + "\n" + ((.stackTrace // "") | split("\n") | map(select(test("Caused by|Exception|Description|Action|^[A-Z]"))) | .[0:14] | join("\n"))' 2>/dev/null \
    | grep -v '^\s*$' | cut -c1-300 | head -45)"
  [ -n "$body" ] || body="$(docker logs "$1" 2>&1 | grep -E 'ERROR|Exception|Caused by' | tail -12 | cut -c1-300)"
  printf '::error title=%s log::%s\n' "$1" "$(printf '%s' "$body" | sed ':a;N;$!ba;s/\n/%0A/g')"
}

# app NAME [extra docker args...] — every deployable gets the shared platform settings.
app() {
  local name="$1"; shift
  docker run -d --name "$name" --network "$NET" \
    -e DATABASE_URL="$DB" -e REDIS_URL="$REDIS" -e KAFKA_BOOTSTRAP_SERVERS="$KAFKA" \
    -e JWT_SECRET="$JWT" -e INTERNAL_API_KEY="$KEY" -e SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=4 \
    "$@" "jh/$name"
}

app identity-app -e PORT=8081 \
  -e OTP_FIXED_CODE=123456 -e SMS_PROVIDER=log -e EMAIL_PROVIDER=log \
  -e BOOTSTRAP_ADMIN_USERNAME=owner -e BOOTSTRAP_ADMIN_PASSWORD=ci-admin-password-1 \
  -e BOOTSTRAP_ADMIN_MOBILE=+919999999999 \
  -e KMS_PROVIDER=local -e KMS_LOCAL_DATA_KEY="$KMS" -e KMS_DATA_KEY="$KMS" -e GEOCODING_PROVIDER=noop \
  -e BOOKING_CLIENT=http -e BOOKING_SERVICE_URL=http://jobs-app:8084 -e BOOKING_SERVICE_URI=http://jobs-app:8084 \
  -e CATALOG_SERVICE_URI=http://jobs-app:8085 -e VERIFICATION_SERVICE_URL=http://identity-app:8094 \
  -e AUTH_SERVICE_URL=http://identity-app:8081 -e PROVIDER_SERVICE_URL=http://identity-app:8083 \
  -e BANK_ACCOUNT_VERIFICATION=manual
app jobs-app -e PORT=8084 \
  -e CATALOG_CACHE=memory -e PRICING_CACHE=memory -e PROMOTION_SERVICE_URL=http://jobs-app:8098 \
  -e CATALOG_CLIENT=http -e CATALOG_SERVICE_URL=http://jobs-app:8085 \
  -e PRICING_CLIENT=http -e PRICING_ENGINE_URL=http://jobs-app:8086 \
  -e CUSTOMER_CLIENT=http -e CUSTOMER_SERVICE_URL=http://identity-app:8082 \
  -e PROVIDER_SERVICE_URL=http://identity-app:8083 -e BOOKING_SERVICE_URL=http://jobs-app:8084 \
  -e NOTIFICATION_SERVICE_URL=http://engagement-app:8090 \
  -e MEDIA_STORAGE_PROVIDER=local -e LOCATION_STORE=redis
app money-app -e PORT=8088 \
  -e KMS_DATA_KEY="$KMS" -e RAZORPAY_WEBHOOK_SECRET=ci-rzp -e STRIPE_WEBHOOK_SECRET=ci-stripe \
  -e BOOKING_SERVICE_URL=http://jobs-app:8084 -e PROVIDER_SERVICE_URL=http://identity-app:8083 \
  -e PROVIDER_WALLET_CLIENT=http -e NOTIFICATION_SERVICE_URL=http://engagement-app:8090 \
  -e PAYMENT_DEFAULT_GATEWAY=simulator -e PAYMENT_SIMULATOR_ENABLED=true \
  -e PAYMENT_SIMULATOR_SECRET=ci-simulator-secret
app engagement-app -e PORT=8095 \
  -e PREFERENCE_SERVICE_URL=http://identity-app:8082 -e AUTH_SERVICE_URL=http://identity-app:8081
app api-gateway -p 8080:8080 -e PORT=8080 \
  -e AUTH_SERVICE_URI=http://identity-app:8081 -e AUTH_INTROSPECT_URI=http://identity-app:8081/auth/introspect \
  -e CUSTOMER_SERVICE_URI=http://identity-app:8082 -e PROVIDER_SERVICE_URI=http://identity-app:8083 \
  -e VERIFICATION_SERVICE_URI=http://identity-app:8094 \
  -e BOOKING_SERVICE_URI=http://jobs-app:8084 -e CATALOG_SERVICE_URI=http://jobs-app:8085 \
  -e PRICING_ENGINE_URI=http://jobs-app:8086 -e DISPATCH_ENGINE_URI=http://jobs-app:8087 \
  -e LOCATION_SERVICE_URI=http://jobs-app:8092 -e CHAT_SERVICE_URI=http://jobs-app:8093 \
  -e PROMOTION_SERVICE_URI=http://jobs-app:8098 \
  -e PAYMENT_SERVICE_URI=http://money-app:8088 -e INVOICE_SERVICE_URI=http://money-app:8089 \
  -e NOTIFICATION_SERVICE_URI=http://engagement-app:8090 -e COMPLAINT_SERVICE_URI=http://engagement-app:8091 \
  -e ADMIN_SERVICE_URI=http://engagement-app:8095 -e REPORTING_SERVICE_URI=http://engagement-app:8096 \
  -e RATING_SERVICE_URI=http://engagement-app:8097

for web in admin-portal:3000 customer-app:3001 provider-app:3002; do
  n="${web%%:*}"; p="${web##*:}"
  docker run -d --name "$n" --network "$NET" -p "$p:$p" -e PORT="$p" \
    -e API_GATEWAY_URL=http://api-gateway:8080 "jh/$n"
done

# Every hosted service must answer its own health check: name:port pairs per deployable.
CHECKS="identity-app:8081 identity-app:8082 identity-app:8083 identity-app:8094
  jobs-app:8085 jobs-app:8086 jobs-app:8098 jobs-app:8084 jobs-app:8087 jobs-app:8092 jobs-app:8093
  money-app:8088 money-app:8089
  engagement-app:8099 engagement-app:8090 engagement-app:8091 engagement-app:8097 engagement-app:8095 engagement-app:8096
  api-gateway:8080"
for s in $CHECKS; do
  name="${s%%:*}"; port="${s##*:}"
  for i in $(seq 1 120); do
    if docker run --rm --network "$NET" curlimages/curl:8.10.1 -fsS "http://$name:$port/health" >/dev/null 2>&1; then
      echo "$name:$port is up"; continue 2
    fi
    if [ "$(docker inspect -f '{{.State.Running}}' "$name")" != "true" ]; then
      echo "::error::$name exited"; annotate_logs "$name"; docker logs "$name" 2>&1 | tail -60; exit 1
    fi
    sleep 3
  done
  echo "::error::$name:$port not healthy in time"; annotate_logs "$name"; docker logs "$name" 2>&1 | tail -60; exit 1
done

# Starter data and app addresses, exactly as on Railway.
seed() {
  docker run "$@" --network "$NET" -e DATABASE_URL="$DB" \
    -e AUTH_URL=http://identity-app:8081 -e PRICING_URL=http://jobs-app:8086 \
    -e ADMIN_USERNAME=owner -e ADMIN_PASSWORD=ci-admin-password-1 \
    -e CUSTOMER_APP_URL=http://localhost:3001 -e PROVIDER_APP_URL=http://localhost:3002 \
    -e ADMIN_PORTAL_URL=http://localhost:3000 jh/seed
}
seed --name seed \
  || { printf '::error title=seed::%s\n' "$(docker logs seed 2>&1 | tail -8 | sed ':a;N;$!ba;s/\n/%0A/g')"; exit 1; }
# Running it twice must change nothing.
seed --rm
echo "stack ready"
