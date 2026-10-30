#!/usr/bin/env bash
# Walks through Zynqo's behaviour against a running example application.
#
#   docker compose up -d
#   export DEMO_PASSWORD=change-me
#   ../../mvnw spring-boot:run        # in another terminal
#   ./scripts/demo.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
: "${DEMO_PASSWORD:?Set DEMO_PASSWORD to the password the application was started with}"
KEY="demo-$(date +%s)-$RANDOM"
REF="ref-$RANDOM"
BODY="{\"reference\":\"$REF\",\"sku\":\"book-42\",\"quantity\":2}"

post() { # user key body
  curl -sS -i -u "$1:$DEMO_PASSWORD" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $2" -d "$3" "$BASE_URL/api/orders"
  echo; echo
}

echo "== 1. First request creates the order (201)"
post alice "$KEY" "$BODY"

echo "== 2. Same key, same body: stored response replayed (201, Idempotent-Replayed: true)"
post alice "$KEY" "$BODY"

echo "== 3. Same key, different body: rejected (422 FINGERPRINT_MISMATCH)"
post alice "$KEY" "{\"reference\":\"$REF\",\"sku\":\"book-42\",\"quantity\":3}"

echo "== 4. Same key, different user: independent request (bob's own order, 201)"
post bob "$KEY" "$BODY"

echo "== 5. Missing key: rejected (400 KEY_MISSING)"
curl -sS -i -u "alice:$DEMO_PASSWORD" -H 'Content-Type: application/json' -d "$BODY" "$BASE_URL/api/orders"
echo; echo

echo "== 6. New key, same business reference: the database constraint rejects it (409)"
post alice "$KEY-new" "$BODY"

echo "== 7. Ten concurrent requests with one fresh key: one 201, the rest 409 or replayed 201"
CKEY="concurrent-$RANDOM"
CBODY="{\"reference\":\"c-$RANDOM\",\"sku\":\"pen\",\"quantity\":1}"
for _ in $(seq 1 10); do
  curl -sS -o /dev/null -w '%{http_code}\n' -u "alice:$DEMO_PASSWORD" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $CKEY" -d "$CBODY" "$BASE_URL/api/orders" &
done
wait
echo

echo "== 8. Redis unavailable: run 'docker compose stop redis', then:"
echo "   curl -i -u alice:\$DEMO_PASSWORD -H 'Content-Type: application/json' -H 'Idempotency-Key: k' -d '$BODY' $BASE_URL/api/orders"
echo "   -> 503 STORAGE_UNAVAILABLE with Retry-After; the order is NOT created."
