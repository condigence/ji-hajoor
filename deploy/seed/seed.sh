#!/bin/sh
# Seeds the catalog and pricing parameters once the services have created their
# tables. Needs:
#   DATABASE_URL     postgres://user:pass@host:port/db (the shared database)
#   AUTH_URL         e.g. http://auth-service.railway.internal:8081
#   PRICING_URL      e.g. http://pricing-engine.railway.internal:8086
#   ADMIN_USERNAME / ADMIN_PASSWORD   a staff account with the ADMIN role
set -eu

: "${DATABASE_URL:?DATABASE_URL is required}"
: "${AUTH_URL:?AUTH_URL is required}"
: "${PRICING_URL:?PRICING_URL is required}"
: "${ADMIN_USERNAME:?ADMIN_USERNAME is required}"
: "${ADMIN_PASSWORD:?ADMIN_PASSWORD is required}"

q() { psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -tA -c "$1"; }

echo "seed: waiting for catalog and pricing tables"
i=0
until [ "$(q "SELECT count(*) FROM information_schema.tables WHERE (table_schema, table_name) IN (('catalog','service_category'),('catalog','service_subcategory_skill_tag'),('pricing','pricing_parameters'))" 2>/dev/null || echo 0)" = "3" ]; do
  i=$((i + 1)); [ "$i" -gt 120 ] && { echo "seed: tables never appeared" >&2; exit 1; }
  sleep 5
done

if [ "$(q 'SELECT count(*) FROM catalog.service_category')" = "0" ]; then
  echo "seed: catalog empty, loading starter catalog"
  psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -q -f /seed/seed-catalog.sql
else
  echo "seed: catalog already has categories, leaving it alone"
fi

MISSING="$(q "SELECT s.id || ' ' || s.base_price FROM catalog.service_subcategory s
             WHERE NOT EXISTS (SELECT 1 FROM pricing.pricing_parameters p WHERE p.subcategory_id = s.id)")"
if [ -z "$MISSING" ]; then
  echo "seed: every subcategory already has pricing"
  exit 0
fi

echo "seed: signing in as $ADMIN_USERNAME"
i=0
until TOKEN="$(curl -fsS -H 'Content-Type: application/json' \
      -d "$(jq -n --arg u "$ADMIN_USERNAME" --arg p "$ADMIN_PASSWORD" '{username:$u,password:$p}')" \
      "$AUTH_URL/auth/login/password" | jq -er .accessToken)"; do
  i=$((i + 1)); [ "$i" -gt 60 ] && { echo "seed: admin sign-in failed" >&2; exit 1; }
  sleep 5
done


echo "$MISSING" | while read -r id base; do
  [ -z "$id" ] && continue
  body="$(jq -n --arg id "$id" --argjson base "$base" '{subcategoryId:$id, basePrice:$base,
          perKmRate:8.00, maxTravelCharge:150.00, nightSurcharge:100.00, weekendSurcharge:75.00,
          platformFeeRate:0.10, taxRate:0.18, emergencyMultiplier:2.0, surgeMultiplier:1.0,
          overrideFloor:1.00, overrideCeiling:99999.00}')"
  n=0
  until curl -fsS -o /dev/null -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
        -d "$body" "$PRICING_URL/admin/pricing/parameters"; do
    n=$((n + 1)); [ "$n" -gt 24 ] && { echo "seed: pricing for $id FAILED" >&2; exit 1; }
    sleep 5
  done
  echo "seed: priced $id (base $base)"
done

left="$(q "SELECT count(*) FROM catalog.service_subcategory s
          WHERE NOT EXISTS (SELECT 1 FROM pricing.pricing_parameters p WHERE p.subcategory_id = s.id)")"
[ "$left" = "0" ] || { echo "seed: $left subcategories still without pricing" >&2; exit 1; }
echo "seed: done"
