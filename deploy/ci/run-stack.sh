#!/usr/bin/env bash
# Starts the customer booking stack in Docker the way it runs on Railway:
# one Postgres, password-protected Redis, single-node Kafka, and each service
# configured only through PORT / DATABASE_URL / REDIS_URL / KAFKA_BOOTSTRAP_SERVERS
# plus the same per-service variables used on Railway. Used by the
# "Booking flow" workflow. Images are expected as jh/<service>.
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

# Report a container's last log lines as a GitHub annotation (job logs are not always readable).
annotate_logs() {
  printf '::error title=%s log::%s\n' "$1" \
    "$(docker logs "$1" 2>&1 | grep -E 'ERROR|Exception|Caused by|APPLICATION FAILED|Description|Action|required' | tail -12 | cut -c1-220 | sed ':a;N;$!ba;s/\n/%0A/g')"
}

# svc NAME PORT [extra -e args...]
svc() {
  local name="$1" port="$2"; shift 2
  docker run -d --name "$name" --network "$NET" \
    -e PORT="$port" -e DATABASE_URL="$DB" -e JWT_SECRET="$JWT" -e INTERNAL_API_KEY="$KEY" \
    -e SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=4 \
    "$@" "jh/$name"
}

svc auth-service 8081 -e REDIS_URL="$REDIS" -e OTP_FIXED_CODE=123456 \
  -e BOOTSTRAP_ADMIN_USERNAME=owner -e BOOTSTRAP_ADMIN_PASSWORD=ci-admin-password-1 \
  -e BOOTSTRAP_ADMIN_MOBILE=+919999999999
svc catalog-service 8085 -e REDIS_URL="$REDIS" -e CATALOG_CACHE=memory
svc customer-service 8082 -e KMS_PROVIDER=local -e KMS_LOCAL_DATA_KEY="$KMS" -e GEOCODING_PROVIDER=noop \
  -e BOOKING_SERVICE_URL=http://booking-service:8084
svc pricing-engine 8086 -e REDIS_URL="$REDIS" -e PRICING_CACHE=memory \
  -e PROMOTION_SERVICE_URL=http://promotion-service:8098
svc booking-service 8084 -e KAFKA_BOOTSTRAP_SERVERS="$KAFKA" \
  -e CATALOG_CLIENT=http -e CATALOG_SERVICE_URL=http://catalog-service:8085 \
  -e PRICING_CLIENT=http -e PRICING_ENGINE_URL=http://pricing-engine:8086 \
  -e CUSTOMER_CLIENT=http -e CUSTOMER_SERVICE_URL=http://customer-service:8082 \
  -e PROVIDER_SERVICE_URL=http://provider-service:8083 -e MEDIA_STORAGE_PROVIDER=local
svc dispatch-engine 8087 -e REDIS_URL="$REDIS" -e KAFKA_BOOTSTRAP_SERVERS="$KAFKA" \
  -e PROVIDER_SERVICE_URL=http://provider-service:8083 -e BOOKING_SERVICE_URL=http://booking-service:8084 \
  -e NOTIFICATION_SERVICE_URL=http://notification-service:8090 -e CUSTOMER_SERVICE_URL=http://customer-service:8082 \
  -e CATALOG_SERVICE_URL=http://catalog-service:8085
svc provider-service 8083 -e KMS_PROVIDER=local -e KMS_DATA_KEY="$KMS" -e BOOKING_CLIENT=http \
  -e BOOKING_SERVICE_URI=http://booking-service:8084 -e CATALOG_SERVICE_URI=http://catalog-service:8085 \
  -e VERIFICATION_SERVICE_URL=http://verification-service:8094 -e AUTH_SERVICE_URL=http://auth-service:8081 \
  -e BANK_ACCOUNT_VERIFICATION=manual
svc verification-service 8094 -e PROVIDER_SERVICE_URL=http://provider-service:8083
svc location-service 8092 -e REDIS_URL="$REDIS" -e KAFKA_BOOTSTRAP_SERVERS="$KAFKA" -e LOCATION_STORE=redis
svc payment-service 8088 -e REDIS_URL="$REDIS" -e KMS_DATA_KEY="$KMS" \
  -e RAZORPAY_WEBHOOK_SECRET=ci-rzp -e STRIPE_WEBHOOK_SECRET=ci-stripe \
  -e BOOKING_SERVICE_URL=http://booking-service:8084 -e PROVIDER_SERVICE_URL=http://provider-service:8083 \
  -e PROVIDER_WALLET_CLIENT=http -e PAYMENT_DEFAULT_GATEWAY=simulator -e PAYMENT_SIMULATOR_ENABLED=true \
  -e PAYMENT_SIMULATOR_SECRET=ci-simulator-secret
svc notification-service 8090 -e KAFKA_BOOTSTRAP_SERVERS="$KAFKA" \
  -e PREFERENCE_SERVICE_URL=http://customer-service:8082 -e AUTH_SERVICE_URL=http://auth-service:8081
svc outbox-processor 8099 -e KAFKA_BOOTSTRAP_SERVERS="$KAFKA"
svc admin-service 8095
svc api-gateway 8080 -p 8080:8080 -e REDIS_URL="$REDIS" \
  -e AUTH_SERVICE_URI=http://auth-service:8081 -e AUTH_INTROSPECT_URI=http://auth-service:8081/auth/introspect \
  -e CUSTOMER_SERVICE_URI=http://customer-service:8082 -e PROVIDER_SERVICE_URI=http://provider-service:8083 \
  -e BOOKING_SERVICE_URI=http://booking-service:8084 -e CATALOG_SERVICE_URI=http://catalog-service:8085 \
  -e PRICING_ENGINE_URI=http://pricing-engine:8086 -e DISPATCH_ENGINE_URI=http://dispatch-engine:8087 \
  -e PAYMENT_SERVICE_URI=http://payment-service:8088 -e NOTIFICATION_SERVICE_URI=http://notification-service:8090 \
  -e LOCATION_SERVICE_URI=http://location-service:8092 -e VERIFICATION_SERVICE_URI=http://verification-service:8094 \
  -e ADMIN_SERVICE_URI=http://admin-service:8095

docker run -d --name customer-app --network "$NET" -p 3001:3001 -e PORT=3001 \
  -e API_GATEWAY_URL=http://api-gateway:8080 jh/customer-app
docker run -d --name provider-app --network "$NET" -p 3002:3002 -e PORT=3002 \
  -e API_GATEWAY_URL=http://api-gateway:8080 jh/provider-app

SERVICES="auth-service:8081 catalog-service:8085 customer-service:8082 pricing-engine:8086 booking-service:8084
  dispatch-engine:8087 provider-service:8083 verification-service:8094 location-service:8092 payment-service:8088
  notification-service:8090 outbox-processor:8099 admin-service:8095 api-gateway:8080"
for s in $SERVICES; do
  name="${s%%:*}"; port="${s##*:}"
  for i in $(seq 1 100); do
    if docker run --rm --network "$NET" curlimages/curl:8.10.1 -fsS "http://$name:$port/health" >/dev/null 2>&1; then
      echo "$name is up"; continue 2
    fi
    if [ "$(docker inspect -f '{{.State.Running}}' "$name")" != "true" ]; then
      echo "::error::$name exited"; annotate_logs "$name"; docker logs "$name" 2>&1 | tail -60; exit 1
    fi
    sleep 3
  done
  echo "::error::$name not healthy in time"; annotate_logs "$name"; docker logs "$name" 2>&1 | tail -60; exit 1
done

# Starter data, exactly as on Railway.
docker run --name seed --network "$NET" -e DATABASE_URL="$DB" \
  -e AUTH_URL=http://auth-service:8081 -e PRICING_URL=http://pricing-engine:8086 \
  -e ADMIN_USERNAME=owner -e ADMIN_PASSWORD=ci-admin-password-1 jh/seed \
  || { printf '::error title=seed::%s\n' "$(docker logs seed 2>&1 | tail -8 | sed ':a;N;$!ba;s/\n/%0A/g')"; exit 1; }
# Running it twice must change nothing.
docker run --rm --network "$NET" -e DATABASE_URL="$DB" \
  -e AUTH_URL=http://auth-service:8081 -e PRICING_URL=http://pricing-engine:8086 \
  -e ADMIN_USERNAME=owner -e ADMIN_PASSWORD=ci-admin-password-1 jh/seed

echo "stack ready"
