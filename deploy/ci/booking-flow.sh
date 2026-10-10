#!/usr/bin/env bash
# Drives one customer booking end to end through the API gateway, as the
# customer and provider apps do: OTP sign-in, address, catalog, price estimate,
# booking, dispatch offer, acceptance, the job itself, live location, payment.
# Every step must pass. GATEWAY defaults to http://localhost:8080.
set -uo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
OTP="${OTP:-123456}"
ADMIN_USER="${ADMIN_USER:-owner}"
ADMIN_PASS="${ADMIN_PASS:-ci-admin-password-1}"
STAMP="$(date +%s | tail -c 9)"
MOBILE="+9198$STAMP"
PROVIDER_MOBILE="+9197$STAMP"
FAILED=0

ok()   { printf '  PASS  %s\n' "$1"; }
bad()  { printf '  FAIL  %s -- %s\n' "$1" "$2"; FAILED=1
         printf '::error title=%s::%s\n' "$1" "$(printf '%s' "$2" | tr '\n' ' ' | head -c 900)"; }
die()  { bad "$1" "$2"; printf '\nStopping: later steps depend on this one.\n'; exit 1; }
# Railway's edge terminates TLS; the gateway insists on X-Forwarded-Proto: https.
api()  { curl -sS -H 'X-Forwarded-Proto: https' "$@"; }
code() { curl -sS -o /dev/null -w '%{http_code}' -H 'X-Forwarded-Proto: https' "$@"; }
JSON='Content-Type: application/json'

echo "== A new provider onboards through the provider app's API"
c="$(code -X POST -H "$JSON" -d "{\"mobileNumber\":\"$PROVIDER_MOBILE\",\"role\":\"SERVICE_PROVIDER\"}" "$GATEWAY/auth/register/otp")"
[ "$c" = 202 ] && ok "provider code requested" || die "provider code requested" "HTTP $c"
PSESSION="$(api -X POST -H "$JSON" -d "{\"mobileNumber\":\"$PROVIDER_MOBILE\",\"otp\":\"$OTP\"}" "$GATEWAY/auth/register/verify")"
PTOKEN="$(jq -r '.accessToken // empty' <<<"$PSESSION")"; PROVIDER_ID="$(jq -r '.userId // empty' <<<"$PSESSION")"
[ -n "$PTOKEN" ] && ok "provider signed in" || die "provider signed in" "$(head -c 200 <<<"$PSESSION")"
P="Authorization: Bearer $PTOKEN"
c="$(code -H "$P" "$GATEWAY/providers/me/profile")"
[ "$c" = 404 ] && ok "new provider has no profile yet (app sends them to onboarding)" || bad "new provider has no profile" "HTTP $c"

PCATS="$(api -H "$P" "$GATEWAY/catalog/categories")"
PROFILE="$(jq -c '[.[] | select(.name == "Plumbing")][0] as $c
  | {displayName: "CI Plumber", yearsExperience: 5, serviceRadiusKm: 10,
     baseLatitude: 25.5580, baseLongitude: 84.6620,
     categories: [{categoryId: $c.id, subcategoryIds: [$c.subcategories[].id]}],
     skillTags: ([$c.subcategories[].skillTags[]] | unique)}' <<<"$PCATS")"
c="$(code -X PUT -H "$P" -H "$JSON" -d "$PROFILE" "$GATEWAY/providers/me/profile")"
[ "$c" = 200 ] && ok "provider profile saved (services + base location)" || die "provider profile saved" "HTTP $c"

printf '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==' | base64 -d > /tmp/photo.jpg
c="$(code -X POST -H "$P" -F "GOVERNMENT_ID=@/tmp/photo.jpg;type=image/jpeg" -F "ADDRESS_PROOF=@/tmp/photo.jpg;type=image/jpeg" \
      -F "SKILL_CERTIFICATION=@/tmp/photo.jpg;type=image/jpeg" "$GATEWAY/verifications/$PROVIDER_ID/documents")"
[ "$c" = 200 ] || [ "$c" = 201 ] && ok "verification documents uploaded (HTTP $c)" || die "verification documents uploaded" "HTTP $c"

ASESSION="$(api -X POST -H "$JSON" -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" "$GATEWAY/auth/login/password")"
ATOKEN="$(jq -r '.accessToken // empty' <<<"$ASESSION")"
[ -n "$ATOKEN" ] && ok "admin signed in" || die "admin signed in" "$(head -c 200 <<<"$ASESSION")"
A="Authorization: Bearer $ATOKEN"
api -H "$A" "$GATEWAY/admin/verification/queue" | grep -q "$PROVIDER_ID" && ok "provider waits in the admin verification queue" || bad "provider in verification queue" "not listed"
c="$(code -X POST -H "$A" -H "$JSON" -d '{"decision":"APPROVE"}' "$GATEWAY/admin/verification/$PROVIDER_ID/decision")"
[ "$c" = 204 ] && ok "admin approved the provider (one step)" || die "admin approved the provider" "HTTP $c"
VS="$(api -H "$P" "$GATEWAY/verifications/$PROVIDER_ID" | jq -r '.status // empty')"
[ "$VS" = APPROVED ] && ok "provider is APPROVED" || die "provider is APPROVED" "status $VS"

echo "== Customer signs in with the fixed test code ($MOBILE)"
c="$(code -X POST -H "$JSON" -d "{\"mobileNumber\":\"$MOBILE\",\"role\":\"CUSTOMER\"}" "$GATEWAY/auth/register/otp")"
[ "$c" = 202 ] && ok "code requested" || die "code requested" "HTTP $c"
SESSION="$(api -X POST -H "$JSON" -d "{\"mobileNumber\":\"$MOBILE\",\"otp\":\"$OTP\"}" "$GATEWAY/auth/register/verify")"
TOKEN="$(jq -r '.accessToken // empty' <<<"$SESSION")"; CUSTOMER_ID="$(jq -r '.userId // empty' <<<"$SESSION")"
[ -n "$TOKEN" ] && ok "signed in" || die "signed in" "$(head -c 200 <<<"$SESSION")"
C="Authorization: Bearer $TOKEN"

echo "== Customer prepares a booking"
ADDR="$(api -X POST -H "$C" -H "$JSON" -d '{"label":"Home","lat":25.5571,"lng":84.6612}' "$GATEWAY/customers/$CUSTOMER_ID/addresses")"
ADDRESS_ID="$(jq -r '.addressId // empty' <<<"$ADDR")"
[ -n "$ADDRESS_ID" ] && ok "address saved" || die "address saved" "$(head -c 200 <<<"$ADDR")"

CATS="$(api -H "$C" "$GATEWAY/catalog/categories")"
CATEGORY_ID="$(jq -r '.. | objects | select(.name? == "Plumbing") | .id' <<<"$CATS" | head -1)"
SUB_ID="$(jq -r '.. | objects | select(.name? == "Tap / Faucet Repair") | .id' <<<"$CATS" | head -1)"
[ -n "$SUB_ID" ] && ok "catalog lists Plumbing > Tap / Faucet Repair" || die "catalog" "$(head -c 300 <<<"$CATS")"

EST="$(api -X POST -H "$C" -H "$JSON" -d "{\"subcategoryId\":\"$SUB_ID\",\"emergency\":false}" "$GATEWAY/pricing/estimate")"
TOTAL="$(jq -r '.total // .estimatedTotal // .grandTotal // empty' <<<"$EST")"
[ -n "$TOTAL" ] && ok "price estimate ($TOTAL)" || bad "price estimate" "$(head -c 300 <<<"$EST")"

AT="$(date -u -d '+6 hours' +%Y-%m-%dT%H:%M:%SZ)"
CREATED="$(api -X POST -H "$C" -H "$JSON" -d "{\"addressId\":\"$ADDRESS_ID\",\"categoryId\":\"$CATEGORY_ID\",\"subcategoryId\":\"$SUB_ID\",\"emergency\":false,\"scheduledAt\":\"$AT\",\"description\":\"CI booking flow\"}" "$GATEWAY/bookings")"
REF="$(jq -r '.reference // empty' <<<"$CREATED")"; BOOKING_ID="$(jq -r '.id // .bookingId // empty' <<<"$CREATED")"
[ -n "$REF" ] && ok "booking created ($REF)" || die "booking created" "$(head -c 300 <<<"$CREATED")"
c="$(code -X POST -H "$C" "$GATEWAY/bookings/$REF/confirmation")"
[ "$c" = 200 ] && ok "booking confirmed" || die "booking confirmed" "HTTP $c"
[ -n "$BOOKING_ID" ] || BOOKING_ID="$(docker exec postgres psql -U postgres -d railway -tAc "SELECT id FROM booking.booking WHERE reference = '$REF'")"

c="$(code -H "$C" "$GATEWAY/bookings/history")"
[ "$c" = 200 ] && ok "booking history loads" || bad "booking history loads" "HTTP $c"

echo "== Dispatch offers the job to the onboarded provider"
OFFERED=""
for _ in $(seq 1 90); do
  api -H "$P" "$GATEWAY/dispatch/offers" | grep -q "$BOOKING_ID" && { OFFERED=1; break; }
  sleep 1
done
[ -n "$OFFERED" ] && ok "offer reached the provider (via Kafka)" || die "offer reached the provider" "no offer within 90 s"
c="$(code -X POST -H "$P" "$GATEWAY/dispatch/offers/$BOOKING_ID/accept")"
[ "$c" = 200 ] && ok "provider accepted" || die "provider accepted" "HTTP $c"

status() { api -H "$C" "$GATEWAY/bookings/$BOOKING_ID" | jq -r '.status // empty'; }
S=""; for _ in $(seq 1 20); do S="$(status)"; [ "$S" = PROVIDER_ACCEPTED ] && break; sleep 1; done
[ "$S" = PROVIDER_ACCEPTED ] && ok "customer sees PROVIDER_ACCEPTED" || die "customer sees PROVIDER_ACCEPTED" "status $S"

echo "== Provider does the job"
B="$GATEWAY/bookings/$BOOKING_ID"
c="$(code -X POST -H "$P" "$B/on-the-way")"; [ "$c" = 200 ] && ok "on the way" || bad "on the way" "HTTP $c"
# The customer app opens the live stream with the token in the URL (browser EventSource
# cannot send headers); open it first, then the provider's update must arrive on it.
curl -sSN --max-time 12 -H 'X-Forwarded-Proto: https' -H 'Accept: text/event-stream' \
  "$GATEWAY/locations/$BOOKING_ID/stream?access_token=$TOKEN" > /tmp/stream.txt 2>/dev/null &
STREAM_PID=$!
sleep 3
c="$(code -X POST -H "$P" -H "$JSON" -d '{"latitude":25.5590,"longitude":84.6630}' "$GATEWAY/locations/$BOOKING_ID")"
[ "$c" = 202 ] && ok "provider shares location" || bad "provider shares location" "HTTP $c"
api -H "$C" "$GATEWAY/locations/$BOOKING_ID" | grep -q '"latitude":25.559' && ok "customer sees live location" || bad "customer sees live location" "no snapshot"
for _ in $(seq 1 8); do grep -q '25.559' /tmp/stream.txt && break; sleep 1; done
kill "$STREAM_PID" 2>/dev/null; wait "$STREAM_PID" 2>/dev/null
grep -q '25.559' /tmp/stream.txt && ok "live tracking stream delivers the update (token in URL)" \
  || bad "live tracking stream delivers the update" "stream got: $(head -c 300 /tmp/stream.txt)"
c="$(code -X POST -H "$P" "$B/arrived")"; [ "$c" = 200 ] && ok "arrived" || bad "arrived" "HTTP $c"
printf '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==' | base64 -d > /tmp/photo.jpg
photo() { code -X POST -H "$P" -F "type=$1" -F "file=@/tmp/photo.jpg;type=image/jpeg;filename=$1.jpg" "$B/photos"; }
c="$(photo BEFORE_PHOTO)"; [ "$c" = 204 ] && ok "before-photo uploaded" || bad "before-photo uploaded" "HTTP $c"
c="$(code -X POST -H "$P" "$B/start")"; [ "$c" = 200 ] && ok "job started" || bad "job started" "HTTP $c"
c="$(photo AFTER_PHOTO)"; [ "$c" = 204 ] && ok "after-photo uploaded" || bad "after-photo uploaded" "HTTP $c"
c="$(code -X POST -H "$P" "$B/complete")"; [ "$c" = 200 ] && ok "job completed" || die "job completed" "HTTP $c"

echo "== Customer pays (payment simulator)"
PAY="$(curl -sS -w '\n%{http_code}' -H 'X-Forwarded-Proto: https' -X POST -H "$C" -H "$JSON" -d "{\"bookingId\":\"$BOOKING_ID\",\"method\":\"UPI\"}" "$GATEWAY/payments")"
c="$(tail -1 <<<"$PAY")"
[ "$c" = 201 ] || [ "$c" = 200 ] && ok "payment accepted (HTTP $c)" || bad "payment accepted" "HTTP $c: $(head -1 <<<"$PAY" | head -c 300)"
S=""; for _ in $(seq 1 40); do S="$(status)"; [ "$S" = PAYMENT_COMPLETED ] && break; sleep 1; done
[ "$S" = PAYMENT_COMPLETED ] && ok "booking reached PAYMENT_COMPLETED (via Kafka)" || bad "booking reached PAYMENT_COMPLETED" "status $S"

echo "== Event relay health"
FAILED_EVENTS="$(docker exec postgres psql -U postgres -d railway -tAc "SELECT count(*) FROM outbox.outbox_event WHERE status = 'FAILED'")"
[ "$FAILED_EVENTS" = 0 ] && ok "no failed outbox events" || bad "no failed outbox events" "$FAILED_EVENTS failed"

echo "== The apps are served and proxy to the gateway"
for app in 3001 3002; do
  c="$(curl -sS -o /dev/null -w '%{http_code}' "http://localhost:$app/")"; [ "$c" = 200 ] && ok "app on :$app serves" || bad "app on :$app serves" "HTTP $c"
  c="$(curl -sS -o /dev/null -w '%{http_code}' -H 'X-Forwarded-Proto: https' -H "$C" "http://localhost:$app/api/bookings/history")"
  [ "$c" = 200 ] && ok "app on :$app reaches the API" || bad "app on :$app reaches the API" "HTTP $c"
done

[ "$FAILED" = 0 ] && echo "ALL PASSED" || { echo "SOME CHECKS FAILED"; exit 1; }
