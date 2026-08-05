#!/usr/bin/env bash
# verify-stack.sh — answer "is this stack working?" without reading a log.
#
# History
#   2026-08-05  A. Sigdel  Created.
#
# Usage
#   NEURALWATT_API_KEY=nw-... scripts/verify-stack.sh [router-url]
#
# Sections 1 and 2 check reachability. Section 3 checks the routing decisions
# themselves, which is the part worth having: every endpoint can return 200 while
# every request goes to the most expensive model. That failure costs money
# silently and nothing else in the stack would report it.

set -uo pipefail

ROUTER="${1:-http://127.0.0.1:8080}"
UPSTREAM="https://api.neuralwatt.com/v1"
fail=0

pass() { printf '   PASS  %s\n' "$1"; }
bad() {
    printf '   FAIL  %s\n' "$1"
    fail=1
}

# The tier and reason the router chose, from the header it sets on every reply.
# Prints "<tier>; <reason>", or nothing if the request did not get that far.
route() {
    curl -s -o /dev/null -D - --max-time 60 \
        -X POST "$ROUTER/v1/chat/completions" \
        -H 'content-type: application/json' \
        ${2:+-H "x-wattrouter-tier: $2"} \
        -d "$1" |
        tr -d '\r' | awk -F': ' 'tolower($1) == "x-wattrouter-tier" {print $2}'
}

# A request body with a user message of roughly $1 characters.
body() {
    python3 -c "
import json, sys
size = int(sys.argv[1])
extra = json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}
print(json.dumps({'model': 'auto', 'messages': [{'role': 'user', 'content': 'x' * size}], **extra}))
" "$@"
}

echo "== 1. upstream =="
if [ -z "${NEURALWATT_API_KEY:-}" ]; then
    bad "NEURALWATT_API_KEY is not set; see .env.example"
else
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 \
        -H "Authorization: Bearer $NEURALWATT_API_KEY" "$UPSTREAM/models")
    if [ "$code" = "200" ]; then
        pass "$UPSTREAM/models -> 200"
    else
        bad "$UPSTREAM/models -> $code"
    fi
fi

echo
echo "== 2. router is serving =="
health=$(curl -s --max-time 10 "$ROUTER/healthz")
case "$health" in
    *'"ok"'*) pass "/healthz -> ok" ;;
    *) bad "/healthz -> ${health:-no response} (is wattrouter running?)" ;;
esac

models=$(curl -s --max-time 10 "$ROUTER/v1/models" | tr -d ' \n')
for name in auto heavy code long mid cheap aux; do
    case "$models" in
        *"\"id\":\"$name\""*) : ;;
        *)
            bad "/v1/models omits $name"
            break
            ;;
    esac
done
case "$models" in
    *'"id":"auto"'*) pass "/v1/models advertises auto and every tier" ;;
    *) ;;
esac

echo
echo "== 3. routing decisions =="
# These are the checks that matter. Each asserts a rule that can regress while
# every endpoint still answers 200.

got=$(route "$(body 40)")
case "$got" in
    mid*) pass "a short prompt          -> $got" ;;
    *) bad "a short prompt          -> ${got:-no decision} (expected mid)" ;;
esac

got=$(route "$(body 40 '{"max_tokens": 16}')")
case "$got" in
    aux*background*) pass "a titling request       -> $got" ;;
    *) bad "a titling request       -> ${got:-no decision} (expected aux; background)" ;;
esac

# Over the 190K-token threshold, at roughly four characters per token.
got=$(route "$(body 900000)")
case "$got" in
    long*context-too-large*) pass "an oversized context    -> $got" ;;
    *) bad "an oversized context    -> ${got:-no decision} (expected long; context-too-large)" ;;
esac

got=$(route "$(body 40)" cheap)
case "$got" in
    cheap*pinned*) pass "a pinned tier           -> $got" ;;
    *) bad "a pinned tier           -> ${got:-no decision} (expected cheap; pinned)" ;;
esac

# A pin has to beat every other rule, including the capability one. If this
# regresses, the escape hatch is no longer an escape hatch.
got=$(route "$(body 900000 '{"max_tokens": 16}')" heavy)
case "$got" in
    heavy*pinned*) pass "a pin beating every rule -> $got" ;;
    *) bad "a pin beating every rule -> ${got:-no decision} (expected heavy; pinned)" ;;
esac

echo
if [ "$fail" = "0" ]; then
    echo "ALL CHECKS PASSED"
else
    echo "SOME CHECKS FAILED (see above)"
fi
exit "$fail"
