#!/bin/sh
# Ji-Hajoor test accounts and demo data, for trying all three apps on a live
# environment. Run by seed.sh only when SEED_TEST_DATA=true. Safe to re-run:
# existing accounts are reused and the demo bookings are made only once.
#
#   Customer app   Asha Sharma   +919000090001   OTP (test mode)
#                  Ravi Kumar    +919000090002   OTP (test mode)
#   Provider app   Ramesh Plumbing Works  +919000090011   Plumbing
#                  Suresh Electricals     +919000090012   Electrical
#   Admin portal   testadmin    ADMIN           password TEST_STAFF_PASSWORD
#                  teststaff    SUPPORT_AGENT   password TEST_STAFF_PASSWORD
#
# Demo data: Asha has a completed, paid and reviewed plumbing job done by
# Ramesh; Ravi has an electrical job that Suresh has accepted and is on the way to.
#
# Needs: DATABASE_URL, GATEWAY_URL (the API gateway, private address is fine),
# ADMIN_USERNAME / ADMIN_PASSWORD (an existing admin, to approve the providers),
# TEST_STAFF_PASSWORD (12+ characters, for the two staff accounts).
# Optional: TEST_OTP (default 123456; sign-in must be in fixed-code test mode).
set -u

: "${DATABASE_URL:?DATABASE_URL is required}"
: "${GATEWAY_URL:?GATEWAY_URL is required}"
: "${ADMIN_USERNAME:?ADMIN_USERNAME is required}"
: "${ADMIN_PASSWORD:?ADMIN_PASSWORD is required}"
: "${TEST_STAFF_PASSWORD:?TEST_STAFF_PASSWORD is required}"
OTP="${TEST_OTP:-123456}"
GW="${GATEWAY_URL%/}"
J='Content-Type: application/json'

log()  { echo "test-data: $*"; }
fail() { echo "test-data: FAILED: $*" >&2; exit 1; }
q()    { psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -qtA -c "$1"; }
sqlq() { printf '%s' "$1" | sed "s/'/''/g"; }
# The gateway expects the edge's X-Forwarded-Proto: https.
api()  { curl -sS -H 'X-Forwarded-Proto: https' "$@"; }
code() { curl -sS -o /dev/null -w '%{http_code}' -H 'X-Forwarded-Proto: https' "$@" 2>/dev/null || echo 000; }

[ "${#TEST_STAFF_PASSWORD}" -ge 12 ] || fail "TEST_STAFF_PASSWORD must be at least 12 characters"

log "waiting for the gateway"
i=0
until [ "$(code "$GW/health")" = 200 ]; do
  i=$((i + 1)); [ "$i" -gt 60 ] && fail "gateway not healthy at $GW"
  sleep 5
done

# ---------------------------------------------------------------- staff accounts
# Staff roles cannot self-register and invitations are accepted from an email
# link, so the two staff accounts are written directly, password bcrypt-hashed.
q "CREATE EXTENSION IF NOT EXISTS pgcrypto" >/dev/null
PW_SQL="$(sqlq "$TEST_STAFF_PASSWORD")"
staff() { # username mobile role display-name
  if [ "$(q "SELECT count(*) FROM auth.user_account WHERE username = '$1'")" = 0 ]; then
    q "WITH u AS (
         INSERT INTO auth.user_account (id, created_at, mobile_number, mobile_verified_at, password_hash,
                                        username, display_name, verified, status)
         VALUES (gen_random_uuid(), now(), '$2', now(), crypt('$PW_SQL', gen_salt('bf', 12)),
                 '$1', '$4', true, 'ACTIVE')
         RETURNING id)
       INSERT INTO auth.user_account_role (user_id, role) SELECT id, '$3' FROM u" >/dev/null
    log "created $1 ($3)"
  else
    log "$1 already exists"
  fi
}
staff testadmin +919000090021 ADMIN "Test Admin"
staff teststaff +919000090022 SUPPORT_AGENT "Test Support"

# ---------------------------------------------------------------- sign-in helper
# Sets TOKEN and USER_ID. An existing account is simply signed in again.
signin() { # mobile role
  c="$(code -X POST -H "$J" -d "{\"mobileNumber\":\"$1\",\"role\":\"$2\"}" "$GW/auth/register/otp")"
  [ "$c" = 202 ] || fail "OTP request for $1 (HTTP $c)"
  api -X POST -H "$J" -d "{\"mobileNumber\":\"$1\",\"otp\":\"$OTP\"}" "$GW/auth/register/verify" > /tmp/session.json
  TOKEN="$(jq -r '.accessToken // empty' /tmp/session.json)"
  USER_ID="$(jq -r '.userId // empty' /tmp/session.json)"
  [ -n "$TOKEN" ] || fail "sign-in for $1: $(head -c 200 /tmp/session.json)"
}

api -X POST -H "$J" -d "$(jq -n --arg u "$ADMIN_USERNAME" --arg p "$ADMIN_PASSWORD" '{username:$u,password:$p}')" \
  "$GW/auth/login/password" > /tmp/admin.json
ADMIN_TOKEN="$(jq -r '.accessToken // empty' /tmp/admin.json)"
[ -n "$ADMIN_TOKEN" ] || fail "admin sign-in: $(head -c 200 /tmp/admin.json)"
A="Authorization: Bearer $ADMIN_TOKEN"

CATS="$(api -H "$A" "$GW/catalog/categories")"
cat_id() { printf '%s' "$CATS" | jq -r --arg n "$1" '[.[] | select(.name == $n)][0].id // empty'; }
sub_id() { printf '%s' "$CATS" | jq -r --arg n "$1" '.. | objects | select(.name? == $n) | .id' | head -1; }

printf '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==' \
  | base64 -d > /tmp/photo.jpg

# ---------------------------------------------------------------- providers
provider() { # mobile display-name category lat lng
  signin "$1" SERVICE_PROVIDER
  P="Authorization: Bearer $TOKEN"
  PROVIDER_ID="$USER_ID"
  if [ "$(code -H "$P" "$GW/providers/me/profile")" = 404 ]; then
    body="$(printf '%s' "$CATS" | jq -c --arg cat "$3" --arg name "$2" --argjson lat "$4" --argjson lng "$5" \
      '[.[] | select(.name == $cat)][0] as $c
       | {displayName:$name, yearsExperience:7, serviceRadiusKm:15, baseLatitude:$lat, baseLongitude:$lng,
          categories:[{categoryId:$c.id, subcategoryIds:[$c.subcategories[].id]}],
          skillTags:([$c.subcategories[].skillTags[]] | unique)}')"
    c="$(code -X PUT -H "$P" -H "$J" -d "$body" "$GW/providers/me/profile")"
    [ "$c" = 200 ] || fail "profile for $2 (HTTP $c)"
    log "profile saved for $2 ($3)"
  fi
  status="$(api -H "$P" "$GW/verifications/$PROVIDER_ID" | jq -r '.status // empty')"
  if [ "$status" != APPROVED ]; then
    code -X POST -H "$P" -F "GOVERNMENT_ID=@/tmp/photo.jpg;type=image/jpeg" \
      -F "ADDRESS_PROOF=@/tmp/photo.jpg;type=image/jpeg" \
      -F "SKILL_CERTIFICATION=@/tmp/photo.jpg;type=image/jpeg" "$GW/verifications/$PROVIDER_ID/documents" >/dev/null
    code -X POST -H "$A" -H "$J" -d '{"decision":"APPROVE"}' "$GW/admin/verification/$PROVIDER_ID/decision" >/dev/null
    code -X POST -H "$A" -H "$J" -d '{"outcome":"PASSED","result":"Test account: documents and records checked"}' \
      "$GW/admin/verification/$PROVIDER_ID/background-check" >/dev/null
    status="$(api -H "$P" "$GW/verifications/$PROVIDER_ID" | jq -r '.status // empty')"
    [ "$status" = APPROVED ] || fail "$2 not approved (status $status)"
  fi
  log "$2 is APPROVED"
}
provider +919000090011 "Ramesh Plumbing Works" Plumbing 25.5580 84.6620
PLUMBER_TOKEN="$TOKEN"
provider +919000090012 "Suresh Electricals" Electrical 25.5560 84.6640
ELECTRICIAN_TOKEN="$TOKEN"

# ---------------------------------------------------------------- customers
customer() { # mobile display-name lat lng address-label
  signin "$1" CUSTOMER
  C="Authorization: Bearer $TOKEN"
  CUSTOMER_ID="$USER_ID"
  api -X PUT -H "$C" -F "profile={\"displayName\":\"$2\"};type=application/json" \
    "$GW/customers/$CUSTOMER_ID/profile" > /dev/null
  ADDRESS_ID="$(api -H "$C" "$GW/customers/$CUSTOMER_ID/addresses" | jq -r '(if type == "array" then . else (.addresses // .content // []) end)[0] | (.addressId // .id // empty)' 2>/dev/null)"
  if [ -z "$ADDRESS_ID" ]; then
    ADDRESS_ID="$(api -X POST -H "$C" -H "$J" -d "{\"label\":\"$5\",\"lat\":$3,\"lng\":$4}" \
      "$GW/customers/$CUSTOMER_ID/addresses" | jq -r '.addressId // empty')"
    [ -n "$ADDRESS_ID" ] || fail "address for $2"
  fi
  log "$2 signed up, address ready"
}
customer +919000090001 "Asha Sharma" 25.5571 84.6612 Home
ASHA_TOKEN="$TOKEN"; ASHA_ID="$CUSTOMER_ID"; ASHA_ADDRESS="$ADDRESS_ID"
customer +919000090002 "Ravi Kumar" 25.5549 84.6655 Home
RAVI_TOKEN="$TOKEN"; RAVI_ID="$CUSTOMER_ID"; RAVI_ADDRESS="$ADDRESS_ID"

# ---------------------------------------------------------------- demo bookings
book() { # customer-token address-id category subcategory hours-ahead description
  at="$(date -u -d "@$(( $(date +%s) + $5 * 3600 ))" +%Y-%m-%dT%H:%M:%SZ)"
  body="$(jq -n --arg a "$2" --arg c "$(cat_id "$3")" --arg s "$(sub_id "$4")" --arg at "$at" --arg d "$6" \
          '{addressId:$a, categoryId:$c, subcategoryId:$s, emergency:false, scheduledAt:$at, description:$d}')"
  api -X POST -H "Authorization: Bearer $1" -H "$J" -d "$body" "$GW/bookings" > /tmp/booking.json
  REF="$(jq -r '.reference // empty' /tmp/booking.json)"
  [ -n "$REF" ] || fail "booking $4: $(head -c 200 /tmp/booking.json)"
  BOOKING_ID="$(jq -r '.id // .bookingId // empty' /tmp/booking.json)"
  [ -n "$BOOKING_ID" ] || BOOKING_ID="$(q "SELECT id FROM booking.booking WHERE reference = '$REF'")"
  c="$(code -X POST -H "Authorization: Bearer $1" "$GW/bookings/$REF/confirmation")"
  [ "$c" = 200 ] || fail "confirm $REF (HTTP $c)"
  log "booking $REF confirmed ($4)"
}
accept() { # provider-token booking-id
  i=0
  until api -H "Authorization: Bearer $1" "$GW/dispatch/offers" | grep -q "$2"; do
    i=$((i + 1)); [ "$i" -gt 120 ] && fail "no offer for booking $2"
    sleep 1
  done
  c="$(code -X POST -H "Authorization: Bearer $1" "$GW/dispatch/offers/$2/accept")"
  [ "$c" = 200 ] || fail "accept $2 (HTTP $c)"
  i=0
  until [ "$(api -H "Authorization: Bearer $1" "$GW/bookings/$2" | jq -r '.status // empty')" = PROVIDER_ACCEPTED ]; do
    i=$((i + 1)); [ "$i" -gt 30 ] && fail "booking $2 never reached PROVIDER_ACCEPTED"
    sleep 1
  done
}

if [ "$(q "SELECT count(*) FROM booking.booking WHERE customer_id = '$ASHA_ID'")" = 0 ]; then
  # Asha: a finished job, paid and reviewed.
  book "$ASHA_TOKEN" "$ASHA_ADDRESS" Plumbing "Tap / Faucet Repair" 6 "Kitchen tap is leaking"
  accept "$PLUMBER_TOKEN" "$BOOKING_ID"
  P="Authorization: Bearer $PLUMBER_TOKEN"; B="$GW/bookings/$BOOKING_ID"
  code -X POST -H "$P" "$B/on-the-way" >/dev/null
  code -X POST -H "$P" -H "$J" -d '{"latitude":25.5576,"longitude":84.6616}' "$GW/locations/$BOOKING_ID" >/dev/null
  code -X POST -H "$P" "$B/arrived" >/dev/null
  code -X POST -H "$P" -F "type=BEFORE_PHOTO" -F "file=@/tmp/photo.jpg;type=image/jpeg;filename=before.jpg" "$B/photos" >/dev/null
  code -X POST -H "$P" "$B/start" >/dev/null
  code -X POST -H "$P" -F "type=AFTER_PHOTO" -F "file=@/tmp/photo.jpg;type=image/jpeg;filename=after.jpg" "$B/photos" >/dev/null
  c="$(code -X POST -H "$P" "$B/complete")"; [ "$c" = 200 ] || fail "complete $BOOKING_ID (HTTP $c)"
  c="$(code -X POST -H "Authorization: Bearer $ASHA_TOKEN" -H "$J" -d "{\"bookingId\":\"$BOOKING_ID\",\"method\":\"UPI\"}" "$GW/payments")"
  [ "$c" = 201 ] || [ "$c" = 200 ] || fail "payment for $BOOKING_ID (HTTP $c)"
  i=0
  until [ "$(api -H "Authorization: Bearer $ASHA_TOKEN" "$B" | jq -r '.status // empty')" = PAYMENT_COMPLETED ]; do
    i=$((i + 1)); [ "$i" -gt 60 ] && fail "payment never completed for $BOOKING_ID"
    sleep 1
  done
  log "Asha's plumbing job is done and paid"
  review="{\"bookingId\":\"$BOOKING_ID\",\"overall\":5,\"behavior\":5,\"quality\":5,\"timeliness\":4,\"pricingTransparency\":5,\"reviewText\":\"Fixed the leaking tap quickly and left the kitchen clean.\"}"
  i=0
  until [ "$(code -X POST -H "Authorization: Bearer $ASHA_TOKEN" -H "$J" -d "$review" "$GW/reviews")" = 201 ]; do
    i=$((i + 1)); [ "$i" -gt 30 ] && { log "review window not open yet; skipped the review"; break; }
    sleep 2
  done
  [ "$i" -le 30 ] && log "Asha reviewed Ramesh (5 stars)"
else
  log "Asha already has bookings; demo jobs left as they are"
fi

if [ "$(q "SELECT count(*) FROM booking.booking WHERE customer_id = '$RAVI_ID'")" = 0 ]; then
  # Ravi: a live job, accepted, provider on the way.
  book "$RAVI_TOKEN" "$RAVI_ADDRESS" Electrical "Fan Installation" 8 "Install a new ceiling fan in the bedroom"
  accept "$ELECTRICIAN_TOKEN" "$BOOKING_ID"
  code -X POST -H "Authorization: Bearer $ELECTRICIAN_TOKEN" "$GW/bookings/$BOOKING_ID/on-the-way" >/dev/null
  code -X POST -H "Authorization: Bearer $ELECTRICIAN_TOKEN" -H "$J" -d '{"latitude":25.5555,"longitude":84.6648}' \
    "$GW/locations/$BOOKING_ID" >/dev/null
  log "Ravi's electrical job is accepted, Suresh is on the way"
fi

log "done"
