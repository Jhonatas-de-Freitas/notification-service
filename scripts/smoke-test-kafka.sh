#!/usr/bin/env bash
# Checks the adapter that listens to the order events. It needs the stack started with both
# compose files (see docker-compose.kafka.yml), plus docker, curl and jq.
#
# It plays the order service: publishes to orders.events a message that is not JSON, an event of
# a type this service does not know and the same OrderConfirmed twice. One e-mail must come out.
set -euo pipefail

API_URL=${API_URL:-http://localhost:8084}
MAILPIT_URL=${MAILPIT_URL:-http://localhost:8025}

cd "$(dirname "$0")/.."
compose=(docker compose -f docker-compose.yml -f docker-compose.kafka.yml)

pass() {
  echo "ok    $*"
}

fail() {
  echo "FAIL  $*" >&2
  exit 1
}

new_id() {
  cat /proc/sys/kernel/random/uuid 2> /dev/null || uuidgen | tr 'A-F' 'a-f'
}

# event TYPE PAYLOAD: the envelope of the store, on one line
event() {
  jq -cn --arg eventId "$(new_id)" --arg type "$1" --arg orderId "$order" --argjson payload "$2" \
    '{eventId: $eventId, type: $type, orderId: $orderId, occurredAt: (now | todate), payload: $payload}'
}

# Reads one message per line from the standard input.
publish() {
  "${compose[@]}" exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server kafka:9092 --topic orders.events
}

notifications() {
  curl -fsS "$API_URL/notifications?orderId=$order"
}

inbox() {
  curl -fsS --get "$MAILPIT_URL/api/v1/search" --data-urlencode "query=to:$email"
}

# check DESCRIPTION JQ_FILTER JSON [jq options]
check() {
  jq -e "${@:4}" "$2" <<< "$3" > /dev/null || fail "$1: $3"
  pass "$1"
}

order=$(new_id)
customer="kafka-$(date +%s)-$$"
email="$customer@exemplo.com"
subject="Seu pedido #$(echo "${order:0:8}" | tr 'a-f' 'A-F') foi confirmado"

confirmed=$(event OrderConfirmed "$(jq -cn --arg customer "$customer" --arg email "$email" \
  '{customerId: $customer, customerName: "Ana Souza", customerEmail: $email, totalAmount: 699.80,
    items: [{productId: "11111111-1111-1111-1111-111111111111", productName: "Teclado", quantity: 2, unitPrice: 349.90}]}')")

{
  echo "this is not json"
  event OrderShipped '{"carrier": "Correios"}'
  echo "$confirmed"
  echo "$confirmed"
} | publish
pass "published a broken message, an unknown event and the same OrderConfirmed twice"

for ((waited = 0; waited < 60; waited++)); do
  if notifications | jq -e 'any(.content[]; .channel == "EMAIL" and .status == "SENT")' > /dev/null 2>&1; then
    break
  fi
  sleep 1
done
# Time for the second copy, which is right behind the first one, to be handled too.
sleep 3

check "the event became one e-mail and one push for the customer, both ORDER_CONFIRMED" \
  '.totalElements == 2 and ([.content[].channel] | sort) == ["EMAIL", "PUSH"]
    and all(.content[]; .template == "ORDER_CONFIRMED" and .userId == $customer and .status == "SENT")' \
  "$(notifications)" --arg customer "$customer"
check "the customer got one e-mail, with the order number in the subject" \
  '.messages_count == 1 and .messages[0].Subject == $subject' "$(inbox)" --arg subject "$subject"

dead_letter=$("${compose[@]}" exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 --topic notification-service.dlt --from-beginning --max-messages 1 --timeout-ms 20000 \
  2> /dev/null || true)
grep -qx "this is not json" <<< "$dead_letter" || fail "the broken message is not in notification-service.dlt: '$dead_letter'"
pass "the broken message is in notification-service.dlt"

echo "The order events scenario passed"
