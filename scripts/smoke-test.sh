#!/usr/bin/env bash
# Checks the stack started by docker compose from the outside, the way a caller would: requests
# to the API, then the inbox of Mailpit and the parking lot queue of RabbitMQ. Needs curl and jq.
#
# With the default retry intervals (5s, 15s, 45s) it takes a little over a minute, almost all of
# it waiting for the SMS that never goes through to run out of attempts.
set -euo pipefail

API_URL=${API_URL:-http://localhost:8084}
MAILPIT_URL=${MAILPIT_URL:-http://localhost:8025}
RABBITMQ_URL=${RABBITMQ_URL:-http://localhost:15672}

# Users and addresses are new on every run, so the script can be repeated against the same stack.
run="$(date +%s)-$$"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

pass() {
  echo "ok    $*"
}

fail() {
  echo "FAIL  $*" >&2
  exit 1
}

# request USER RECIPIENT [CHANNELS] [EXTRA]: the JSON of a GENERIC notification. The last three are JSON.
request() {
  jq -cn --arg user "$1" --argjson recipient "$2" --argjson channels "${3:-null}" --argjson extra "${4:-null}" \
    '{userId: $user, recipient: $recipient, channels: $channels, template: "GENERIC",
      params: {title: "Cupom de sexta", message: "Seu cupom chegou."}}
     | with_entries(select(.value != null)) + ($extra // {})'
}

# post BODY [curl options]: leaves the answer in $status, $work/body and $work/headers
post() {
  local body=$1
  shift
  status=$(curl -sS -o "$work/body" -D "$work/headers" -w '%{http_code}' -X POST "$API_URL/notifications" \
    -H 'Content-Type: application/json' -d "$body" "$@")
}

# accepted DESCRIPTION EXPECTED_STATUS: the last post was a 202 and its first notification is in that status
accepted() {
  [[ $status == 202 ]] || fail "$1: expected 202, got $status $(cat "$work/body")"
  [[ $(created status) == "$2" ]] || fail "$1: expected $2, got $(cat "$work/body")"
  pass "$1 -> 202 $2"
}

# created FIELD: a field of the first notification in the last answer
created() {
  jq -r ".notifications[0].$1" "$work/body"
}

notification() {
  curl -fsS "$API_URL/notifications/$1"
}

has_status() {
  notification "$1" | jq -e --arg status "$2" '.status == $status' > /dev/null
}

# wait_for SECONDS DESCRIPTION COMMAND...: tries the command once a second until it succeeds
wait_for() {
  local seconds=$1 description=$2 waited
  shift 2
  for ((waited = 0; waited < seconds; waited++)); do
    if "$@" 2> /dev/null; then
      pass "$description"
      return 0
    fi
    sleep 1
  done
  fail "$description: gave up after ${seconds}s"
}

# check DESCRIPTION JQ_FILTER JSON [jq options]
check() {
  jq -e "${@:4}" "$2" <<< "$3" > /dev/null || fail "$1: $3"
  pass "$1"
}

inbox() {
  curl -fsS --get "$MAILPIT_URL/api/v1/search" --data-urlencode "query=to:$1"
}

inbox_has() {
  inbox "$1" | jq -e --argjson count "$2" '.messages_count == $count' > /dev/null
}

set_preferences() {
  curl -fsS -o /dev/null -X PUT "$API_URL/preferences/$1" -H 'Content-Type: application/json' -d "$2"
}

parked() {
  curl -fsS -u guest:guest -X POST "$RABBITMQ_URL/api/queues/%2F/notifications.parking-lot/get" \
    -H 'Content-Type: application/json' \
    -d '{"count":1000,"ackmode":"ack_requeue_true","encoding":"auto"}' \
    | jq -e --arg id "$1" 'any(.[]; .payload == $id)' > /dev/null
}

parking_lot_gauge_above_zero() {
  curl -fsS "$API_URL/actuator/prometheus" | awk '$1 == "notifications_parking_lot_messages" && $2 >= 1 { found = 1 } END { exit !found }'
}

seconds_from_now() {
  date -u -d "+$1 seconds" +%Y-%m-%dT%H:%M:%SZ 2> /dev/null || date -u -v "+$1S" +%Y-%m-%dT%H:%M:%SZ
}

echo "The two SMS that fail go first: their retries run while everything else is checked"

sms_user="sms-$run"
set_preferences "$sms_user" '{"email":true,"sms":true,"push":true}'

post "$(request "$sms_user" '{"name":"Ana Souza","phone":"+5511999990001"}' '["SMS"]')"
accepted "SMS to a phone ending in 0001" PENDING
flaky=$(created id)

post "$(request "$sms_user" '{"name":"Ana Souza","phone":"+5511999990000"}' '["SMS"]')"
accepted "SMS to a phone ending in 0000" PENDING
dead=$(created id)

wait_for 15 "the 0001 SMS failed and is RETRYING" has_status "$flaky" RETRYING

echo "E-mail"

email="email-$run@exemplo.com"
post "$(request "email-$run" "{\"name\":\"Ana Souza\",\"email\":\"$email\"}" '["EMAIL"]')"
accepted "e-mail" PENDING
wait_for 20 "the e-mail is SENT" has_status "$(created id)" SENT
check "one attempt, a success" '[.attempts[].success] == [true] and .sentAt != null' "$(notification "$(created id)")"
message=$(inbox "$email")
check "Mailpit has one message with the subject and the sender of the store" \
  '.messages_count == 1 and .messages[0].Subject == "Cupom de sexta"
    and .messages[0].From.Address == "loja@exemplo.com" and .messages[0].To[0].Name == "Ana Souza"' "$message"
html=$(curl -fsS "$MAILPIT_URL/api/v1/message/$(jq -r '.messages[0].ID' <<< "$message")")
check "the body greets the recipient and carries the text" \
  '(.HTML | contains("Ana Souza")) and (.HTML | contains("Seu cupom chegou."))' "$html"

echo "Preferences"

prefs_user="prefs-$run"
check "someone who never configured gets e-mail and push, not SMS" \
  '.email == true and .sms == false and .push == true' "$(curl -fsS "$API_URL/preferences/$prefs_user")"
everywhere='{"name":"Ana Souza","email":"prefs-'$run'@exemplo.com","phone":"+5511999990002","deviceToken":"device-abc"}'

post "$(request "$prefs_user" "$everywhere")"
[[ $status == 202 ]] || fail "request without channels: expected 202, got $status $(cat "$work/body")"
check "without channels the preferences choose: e-mail and push" \
  '[.notifications[].channel] == ["EMAIL", "PUSH"]' "$(cat "$work/body")"

post "$(request "$prefs_user" "$everywhere" '["SMS"]')"
accepted "SMS with the channel turned off" SKIPPED
check "the reason is recorded and nothing was attempted" \
  '.skipReason == "CHANNEL_DISABLED" and .attempts == []' "$(notification "$(created id)")"

set_preferences "$prefs_user" '{"email":true,"sms":true,"push":true}'
post "$(request "$prefs_user" "$everywhere" '["SMS"]')"
accepted "the same request after turning SMS on" PENDING
wait_for 20 "that SMS is SENT" has_status "$(created id)" SENT

echo "Idempotency"

twice_user="twice-$run"
twice_email="twice-$run@exemplo.com"
body=$(request "$twice_user" "{\"name\":\"Ana Souza\",\"email\":\"$twice_email\"}" '["EMAIL"]')
post "$body" -H "Idempotency-Key: smoke-$run"
accepted "first request with the key" PENDING
first=$(jq -c '{requestId, ids: [.notifications[].id]}' "$work/body")
wait_for 20 "its e-mail is SENT" has_status "$(created id)" SENT
post "$body" -H "Idempotency-Key: smoke-$run"
accepted "second request with the same key" SENT
[[ $(jq -c '{requestId, ids: [.notifications[].id]}' "$work/body") == "$first" ]] \
  || fail "the second answer is not the first one: $first / $(cat "$work/body")"
pass "the second answer has the request and the notification of the first"
check "the user has one notification" '.totalElements == 1' "$(curl -fsS "$API_URL/notifications?userId=$twice_user")"
sleep 2
check "and one e-mail" '.messages_count == 1' "$(inbox "$twice_email")"

echo "Scheduling"

later_email="later-$run@exemplo.com"
scheduled_at=$(seconds_from_now 10)
post "$(request "later-$run" "{\"name\":\"Ana Souza\",\"email\":\"$later_email\"}" '["EMAIL"]' "{\"scheduledAt\":\"$scheduled_at\"}")"
accepted "e-mail scheduled for 10 seconds from now" SCHEDULED
later=$(created id)
sleep 5
check "halfway there it is still SCHEDULED, with no attempt" \
  '.status == "SCHEDULED" and .attempts == []' "$(notification "$later")"
check "and the inbox is empty" '.messages_count == 0' "$(inbox "$later_email")"
wait_for 20 "after its time it is SENT" has_status "$later" SENT
check "the attempt was made at the scheduled time or after it" \
  '[.attempts[].success] == [true]
    and (.attempts[0].at | sub("\\.[0-9]+"; "") | fromdate) >= ($scheduled | fromdate)' \
  "$(notification "$later")" --arg scheduled "$scheduled_at"
wait_for 5 "and the e-mail arrived" inbox_has "$later_email" 1

echo "Validation and filters"

post '{"userId":"","recipient":{"name":"Ana Souza","email":"not-an-address"},"template":"GENERIC"}'
[[ $status == 400 ]] || fail "invalid request: expected 400, got $status $(cat "$work/body")"
grep -qi '^content-type: application/problem+json' "$work/headers" || fail "invalid request: $(cat "$work/headers")"
check "an invalid request is a 400 problem+json that names the fields" \
  '.status == 400 and .type == "about:blank" and .title != null and .instance == "/notifications"
    and [.errors[].field] == ["recipient.email", "userId"]' "$(cat "$work/body")"
check "the list filters by user and status" \
  '.totalElements == 1 and .content[0].channel == "SMS" and .content[0].status == "SKIPPED"' \
  "$(curl -fsS "$API_URL/notifications?userId=$prefs_user&status=SKIPPED")"

echo "Back to the two SMS"

wait_for 45 "the 0001 SMS is SENT" has_status "$flaky" SENT
check "after two failures and one success" '[.attempts[].success] == [false, false, true]' "$(notification "$flaky")"

wait_for 120 "the 0000 SMS is FAILED" has_status "$dead" FAILED
check "after four failed attempts" \
  '[.attempts[].success] == [false, false, false, false] and .sentAt == null' "$(notification "$dead")"
wait_for 15 "its message is in the parking lot queue" parked "$dead"
wait_for 15 "and the gauge of the parking lot shows it" parking_lot_gauge_above_zero

echo "All scenarios passed"
