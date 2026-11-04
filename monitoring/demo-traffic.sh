#!/bin/sh
# Gives the Grafana dashboard something to show: a steady mix of successful payments and transfers,
# declined authorizations, payments held for review and blocked bursts. Local demo only: it relies
# on the dev token endpoint.
#
#   docker compose --profile demo up
set -eu

apk add --no-cache curl jq > /dev/null

API=http://app:8080
J='Content-Type: application/json'

token() { curl -fsS "$API/api/v1/auth/token" -H "$J" -d "$1" | jq -r .accessToken; }

ADMIN="Authorization: Bearer $(token '{"subject":"demo-ops","roles":["ADMIN"]}')"

customer() {
  curl -fsS "$API/api/v1/customers" -H "$J" -H "$ADMIN" \
    -d "{\"email\":\"demo-$(date +%s%N)-$1@example.com\",\"fullName\":\"Demo $1\"}" | jq -r .id
}

customer_token() { token "{\"subject\":\"$1\",\"roles\":[\"CUSTOMER\"],\"customerId\":\"$1\"}"; }

wallet() {
  curl -fsS "$API/api/v1/accounts" -H "$J" -H "Authorization: Bearer $2" \
    -d "{\"customerId\":\"$1\",\"currency\":\"EUR\"}" | jq -r .id
}

deposit() {
  curl -fsS -o /dev/null "$API/api/v1/accounts/$1/deposits" -H "$J" -H "$ADMIN" \
    -d "{\"amount\":\"$2\",\"currency\":\"EUR\"}"
}

ALICE=$(customer alice); ALICE_T=$(customer_token "$ALICE"); A=$(wallet "$ALICE" "$ALICE_T")
SHOP=$(customer shop);   SHOP_T=$(customer_token "$SHOP");   B=$(wallet "$SHOP" "$SHOP_T")
POOR=$(customer poor);   POOR_T=$(customer_token "$POOR");   C=$(wallet "$POOR" "$POOR_T")
deposit "$A" 1000000.00

pay() { # payer-wallet payer-token amount
  curl -sS "$API/api/v1/payments" -H "$J" -H "Authorization: Bearer $2" \
    -d "{\"payerAccountId\":\"$1\",\"merchantAccountId\":\"$B\",\"amount\":\"$3\",\"currency\":\"EUR\"}"
}

# A merchant whose server is down, so webhook retries and failures show up too.
curl -fsS -o /dev/null "$API/api/v1/webhook-endpoints" -H "$J" -H "Authorization: Bearer $SHOP_T" \
  -d "{\"accountId\":\"$B\",\"url\":\"http://merchant-offline.invalid/hooks\",\"eventTypes\":[\"payment.captured\"]}"

echo "Demo traffic running. Dashboard: http://localhost:3002"
i=0
while true; do
  i=$((i + 1))

  # A normal payment, through to capture.
  P=$(pay "$A" "$ALICE_T" "$((i % 90 + 10)).00" | jq -r '.id // empty')
  if [ -n "$P" ]; then
    curl -sS -o /dev/null -X POST "$API/api/v1/payments/$P/authorize" -H "Authorization: Bearer $ALICE_T"
    curl -sS -o /dev/null -X POST "$API/api/v1/payments/$P/capture" -H "Authorization: Bearer $SHOP_T"
  fi

  # A transfer.
  curl -sS -o /dev/null "$API/api/v1/transfers" -H "$J" -H "Authorization: Bearer $ALICE_T" \
    -d "{\"sourceAccountId\":\"$A\",\"destinationAccountId\":\"$B\",\"amount\":\"5.00\",\"currency\":\"EUR\"}"

  # Every so often: a declined authorization, and a payment held for review.
  if [ $((i % 7)) -eq 0 ]; then
    P=$(pay "$C" "$POOR_T" "25.00" | jq -r '.id // empty')
    [ -n "$P" ] && curl -sS -o /dev/null -X POST "$API/api/v1/payments/$P/authorize" -H "Authorization: Bearer $POOR_T"
  fi
  [ $((i % 11)) -eq 0 ] && pay "$A" "$ALICE_T" "7500.00" > /dev/null

  # Occasionally a burst, which trips the velocity rule (and then the rate limit).
  if [ $((i % 60)) -eq 0 ]; then
    n=0
    while [ "$n" -lt 25 ]; do pay "$C" "$POOR_T" "1.00" > /dev/null; n=$((n + 1)); done
  fi

  sleep 3   # stays under the velocity rule; only the bursts below trip it
done
