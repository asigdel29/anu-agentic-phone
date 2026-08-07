#!/usr/bin/env bash
# verify-stack.sh — answer "is this stack working?" without reading a log.
#
# History
#   2026-08-05  A. Sigdel  Created.
#   2026-08-07  A. Sigdel  Stopped paying for the decision checks. They used to
#                          forward five real completions, two of them carrying
#                          900KB, to assert things the upstream never sees.
#
# Usage
#   NEURALWATT_API_KEY=nw-... scripts/verify-stack.sh [router-url]
#
# Sections 1 and 2 check reachability. Sections 3 and 4 check the routing
# decisions themselves, which is the part worth having: every endpoint can return
# 200 while every request goes to the most expensive model. That failure costs
# money silently and nothing else in the stack would report it.
#
# Those two sections run against a throwaway router pointed at a dead upstream,
# so every request fails in microseconds and still reports the decision it made
# — the tier header is on the failure path too. What they assert is the policy,
# which is compiled in rather than deployed, so a local probe answers for any
# deployment of the same binary. Without the probe they would forward real
# traffic to assert something the provider is not involved in.

set -uo pipefail

ROUTER="${1:-http://127.0.0.1:8080}"
UPSTREAM="https://api.neuralwatt.com/v1"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ROOT
readonly BIN="$ROOT/router/target/release/wattrouter"
# Where the decision checks send their requests. The probe when one could be
# started, the real router otherwise — in which case they cost what they always
# did, and say so.
DECIDE="$ROUTER"
probe_pid=""
fail=0

pass() { printf '   PASS  %s\n' "$1"; }
bad() {
    printf '   FAIL  %s\n' "$1"
    fail=1
}

# The tier and reason the router chose, from the header it sets on every reply.
# Prints "<tier>; <reason>", or nothing if the request did not get that far.
#
# $2 pins a tier, $3 names a session. Against the probe the request always ends
# in a 502, which is fine: the header is set on that path too, and the decision
# is the whole of what these checks read.
route() {
    curl -s -o /dev/null -D - --max-time 60 \
        -X POST "$DECIDE/v1/chat/completions" \
        -H 'content-type: application/json' \
        ${2:+-H "x-wattrouter-tier: $2"} \
        ${3:+-H "x-session-id: $3"} \
        -d "$1" |
        tr -d '\r' | awk -F': ' 'tolower($1) == "x-wattrouter-tier" {print $2}'
}

# Start a router whose upstream refuses connections, and point the decision
# checks at it. Leaves DECIDE alone if the binary is missing, so a checkout that
# has not been built still verifies — more slowly, and against the real
# provider.
start_probe() {
    [ -x "$BIN" ] || return 1
    local port
    port=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')
    NEURALWATT_API_KEY=probe \
        WATTROUTER_UPSTREAM=http://127.0.0.1:1 \
        WATTROUTER_ADDR="127.0.0.1:$port" \
        RUST_LOG=error "$BIN" >/dev/null 2>&1 &
    probe_pid=$!
    for _ in $(seq 1 30); do
        if curl -fsS --max-time 1 "http://127.0.0.1:$port/healthz" >/dev/null 2>&1; then
            DECIDE="http://127.0.0.1:$port"
            return 0
        fi
        ps -p "$probe_pid" >/dev/null 2>&1 || break
        sleep 0.2
    done
    kill "$probe_pid" 2>/dev/null
    probe_pid=""
    return 1
}

# However this ends. A probe left holding a port is a confusing failure for
# whoever runs this next.
#
# Invoked by the trap below, which shellcheck does not follow. Two codes because
# the versions disagree about where to complain: newer ones flag the definition
# as uncalled, older ones flag every line of the body as unreachable.
# shellcheck disable=SC2317,SC2329
cleanup() {
    [ -n "$probe_pid" ] && kill "$probe_pid" 2>/dev/null
    return 0
}
trap cleanup EXIT

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
if start_probe; then
    pass "probing a throwaway router; these cost nothing"
else
    printf '   note  no probe (build with "just up" first); these spend real inference\n'
fi

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
echo "== 4. session stickiness =="
# The cache's larger win, and until now nothing asserted it. A session's tier
# rises and never falls, so a conversation that turns out to need the long tier
# does not drop back to the middle one on its next turn.
#
# Only meaningful against the probe: it needs two requests to reach the same
# process, and a session id the deployment has not already seen.
if [ -z "$probe_pid" ]; then
    printf '   note  skipped; needs the probe\n'
else
    session="verify-$$"

    # Asserted rather than assumed. If CHARS_PER_TOKEN or LONG_CONTEXT_TOKENS
    # ever moves, this stops raising the session and the next check would pass
    # for the wrong reason.
    got=$(route "$(body 900000)" "" "$session")
    case "$got" in
        long*context-too-large*) pass "a session raised to long -> $got" ;;
        *) bad "a session raised to long -> ${got:-no decision} (expected long; context-too-large)" ;;
    esac

    # A short prompt is `mid` on its own. On a session already at long it must
    # stay there, and say sticky.
    got=$(route "$(body 40)" "" "$session")
    case "$got" in
        long*sticky*) pass "a follow-up keeps it    -> $got" ;;
        *) bad "a follow-up keeps it    -> ${got:-no decision} (expected long; sticky)" ;;
    esac

    # And the counter that proves a client is sending the header at all.
    total=$(curl -s --max-time 10 "$DECIDE/metrics" |
        awk '/^wattrouter_requests_with_session_total/ {print $2}')
    if [ "${total:-0}" -ge 2 ]; then
        pass "sessions counted        -> $total"
    else
        bad "sessions counted        -> ${total:-none} (expected at least 2)"
    fi
fi

echo
if [ "$fail" = "0" ]; then
    echo "ALL CHECKS PASSED"
else
    echo "SOME CHECKS FAILED (see above)"
fi
exit "$fail"
