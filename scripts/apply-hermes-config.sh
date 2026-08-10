#!/usr/bin/env bash
# apply-hermes-config.sh: point Hermes at the router, reversibly.
#
# History
#   2026-08-07  A. Sigdel  Created.
#
# Usage
#   scripts/apply-hermes-config.sh            report what would change
#   scripts/apply-hermes-config.sh --apply    change it
#   scripts/apply-hermes-config.sh --revert   put it back, leaf by leaf
#   scripts/apply-hermes-config.sh --force    proceed with the gateway running
#
# Hermes talks to whatever model.provider names, and on this machine that has
# never been the router, so tiering, stickiness, metrics and the fallback chain
# have all been running for nobody. This connects it.
#
# It does not write config.yaml. That file is thousands of bytes of hand-tuned
# personalities, toolsets and MCP servers carrying a _config_version, written
# atomically by Hermes's own serialiser. Every write here goes through
# `hermes config set`, which preserves every key it does not name.
#
# Reporting is the default: an apply that happens because someone typed the
# script's name is the wrong default for something that reconfigures a running
# agent.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ROOT
HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
readonly HERMES_HOME
readonly STATE="$ROOT/deploy/.state"
readonly RECORD="$STATE/hermes-config.prev"

# The leaves, in the order they are written. model.provider is last on purpose:
# it is the switch, and written first there is a window in which Hermes resolves
# the new provider against the old endpoint.
readonly LEAVES=(
    "providers.neuralwatt.request_timeout_seconds|1500"
    "providers.neuralwatt.stale_timeout_seconds|0"
    "model.base_url|http://127.0.0.1:8080/v1"
    "model.default|auto"
    "model.provider|neuralwatt"
)

# Deliberately absent: memory.provider. hermes/config.yaml names zeromem, which
# is not installed in Hermes's interpreter, so setting it would swap a working
# provider for one that cannot load. It belongs with the change that installs it.

step() { printf '\n== %s ==\n' "$1"; }
ok() { printf '   ok    %s\n' "$1"; }
did() { printf '   done  %s\n' "$1"; }
bad() { printf '   FAIL  %s\n' "$1" >&2; }
plan() { printf '   set   %-46s %s\n' "$1" "$2"; }

mode=check
force=0
for arg in "$@"; do
    case "$arg" in
        --check) mode=check ;;
        --apply) mode=apply ;;
        --revert) mode=revert ;;
        --force) force=1 ;;
        *)
            printf 'usage: %s [--check|--apply|--revert] [--force]\n' "$0" >&2
            exit 2
            ;;
    esac
done
readonly mode force

step "preflight"
if ! command -v hermes >/dev/null 2>&1; then
    bad "hermes is required"
    exit 1
fi

# An interpreter that can read YAML, for the raw-file reads below.
#
# Not derived from HERMES_HOME. That names where Hermes keeps its state, which
# is not where Hermes is installed: the systemd unit points it at
# /var/lib/hermes while the checkout lives in a user's home. Candidates are
# tried in order and the first that can import yaml wins, so this works against
# a state directory that has no interpreter beside it.
PYTHON=""
for candidate in \
    "${HERMES_PYTHON:-}" \
    "$HOME/.hermes/hermes-agent/venv/bin/python" \
    "$(command -v python3 2>/dev/null)"; do
    if [ -n "$candidate" ] && [ -x "$candidate" ] &&
        "$candidate" -c 'import yaml' >/dev/null 2>&1; then
        PYTHON="$candidate"
        break
    fi
done
readonly PYTHON

if [ -z "$PYTHON" ]; then
    bad "no interpreter with PyYAML"
    printf '   Set HERMES_PYTHON to one, or install PyYAML for python3.\n'
    exit 1
fi
ok "hermes, and ${PYTHON##*/} for reading the file"

# The gateway caches configuration in its own process. Writing underneath it
# means the running agent keeps the old values and may serialise them back over
# these on its next save, so the change silently half-lands.
#
# gateway.pid is JSON rather than a bare pid, which is worth knowing before
# reading it with `cat`.
gateway_pid() {
    [ -f "$HERMES_HOME/gateway.pid" ] || return 1
    "$PYTHON" - "$HERMES_HOME/gateway.pid" <<'PY' 2>/dev/null
import json, sys
try:
    print(json.load(open(sys.argv[1]))["pid"])
except Exception:
    sys.exit(1)
PY
}

if pid="$(gateway_pid)" && ps -p "$pid" >/dev/null 2>&1; then
    if [ "$force" -eq 1 ]; then
        ok "gateway is running as pid $pid; proceeding because --force"
    elif [ "$mode" = check ]; then
        ok "gateway is running as pid $pid; reporting only"
    else
        bad "the gateway is running as pid $pid"
        printf '   It caches configuration and may write its copy back over this one.\n'
        printf '   Stop it first, or pass --force if you know it is idle.\n'
        exit 1
    fi
else
    ok "no gateway running"
fi

# What each leaf is set to right now, read from the file rather than resolved.
#
# `hermes config get` returns the effective value with defaults merged in, which
# cannot distinguish "absent" from "explicitly set to what the default happens
# to be". Reverting needs that distinction: one calls for `unset`, the other for
# `set` back to the old value.
read_state() {
    "$PYTHON" - "$HERMES_HOME/config.yaml" "$@" <<'PY'
import json, sys, yaml

path, keys = sys.argv[1], sys.argv[2:]
try:
    with open(path) as handle:
        config = yaml.safe_load(handle) or {}
except FileNotFoundError:
    config = {}

out = {}
for key in keys:
    node, present = config, True
    for part in key.split("."):
        if isinstance(node, dict) and part in node:
            node = node[part]
        else:
            present = False
            break
    out[key] = {"present": present, "value": node if present else None}
print(json.dumps(out))
PY
}

# One leaf's current value as a string, or the empty string when absent.
current() {
    printf '%s' "$1" | "$PYTHON" -c "
import json, sys
state = json.load(sys.stdin)[sys.argv[1]]
print('' if not state['present'] else state['value'])
" "$2"
}

keys=()
for leaf in "${LEAVES[@]}"; do keys+=("${leaf%%|*}"); done
state="$(read_state "${keys[@]}")" || {
    bad "cannot read $HERMES_HOME/config.yaml"
    exit 1
}

if [ "$mode" = revert ]; then
    step "revert"
    if [ ! -f "$RECORD" ]; then
        bad "no record at $RECORD; nothing to revert to"
        exit 1
    fi
    # Reverse order, mirroring the reasoning for the forward order: put the
    # provider back first, so nothing is resolving neuralwatt against a base URL
    # that is about to be removed.
    for ((i = ${#LEAVES[@]} - 1; i >= 0; i--)); do
        key="${LEAVES[$i]%%|*}"
        was="$(current "$(cat "$RECORD")" "$key")"
        if [ -n "$was" ]; then
            hermes config set "$key" "$was" --force >/dev/null && did "$key = $was"
        else
            hermes config unset "$key" >/dev/null 2>&1 && did "$key unset"
        fi
    done
    printf '\n   Reverted. The backup beside config.yaml is untouched.\n'
    exit 0
fi

step "what would change"
changes=0
for leaf in "${LEAVES[@]}"; do
    key="${leaf%%|*}"
    want="${leaf##*|}"
    have="$(current "$state" "$key")"
    if [ "$have" = "$want" ]; then
        ok "$key is already $want"
    else
        plan "$key" "${have:-(unset)} -> $want"
        changes=$((changes + 1))
    fi
done

# Enabling is separate from setting, and it fails if the plugin is not on disk:
# `hermes plugins enable` reports "not installed or bundled" rather than
# writing a name nothing will resolve.
plugin_installed=0
if [ -e "$HERMES_HOME/plugins/session_routing" ]; then
    plugin_installed=1
    ok "session_routing is installed"
else
    printf '   note  session_routing is not installed; run "just install-hermes" first\n'
fi

if [ "$mode" = check ]; then
    printf '\n   %s change(s). Re-run with --apply to make them.\n' "$changes"
    exit 0
fi

if [ "$changes" -eq 0 ] && [ "$plugin_installed" -eq 0 ]; then
    printf '\n   Nothing to do.\n'
    exit 0
fi

step "backup"
if [ "$changes" -eq 0 ]; then
    ok "no config change; not writing a backup"
else
    # Only when something will actually change, or a re-run litters the
    # directory with identical copies. The name matches what Hermes's own setup
    # writes, so `ls ~/.hermes` keeps reading uniformly.
    backup="$HERMES_HOME/config.yaml.bak.$(date +%Y%m%d_%H%M%S)"
    cp "$HERMES_HOME/config.yaml" "$backup" && did "${backup##*/}"
    mkdir -p "$STATE"
    printf '%s' "$state" >"$RECORD"
    did "prior values recorded for --revert"
fi

step "apply"
for leaf in "${LEAVES[@]}"; do
    key="${leaf%%|*}"
    want="${leaf##*|}"
    have="$(current "$state" "$key")"
    if [ "$have" = "$want" ]; then
        ok "$key"
    elif hermes config set "$key" "$want" --force >/dev/null; then
        did "$key = $want"
    else
        bad "could not set $key"
        printf '   Stopping here. --revert undoes what landed.\n'
        exit 1
    fi
done

if [ "$plugin_installed" -eq 1 ]; then
    # Spelled out rather than `A && B || C`: enabling something already enabled
    # and failing to enable it at all are different outcomes, and the compact
    # form reports the second as the first.
    if hermes plugins enable session_routing >/dev/null 2>&1; then
        did "session_routing enabled"
    else
        bad "could not enable session_routing"
        printf '   Requests will route, but without a session id, so a follow-up\n'
        printf '   turn is re-scored instead of keeping its tier.\n'
    fi
fi

step "next"
printf '   Hermes now resolves through the router. Check it:\n'
printf '     just up && scripts/verify-stack.sh\n'
printf '   Undo with --revert, which replays the recorded values leaf by leaf.\n'
