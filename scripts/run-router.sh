#!/usr/bin/env bash
# run-router.sh: start the router on the machine you are sitting at.
#
# History
#   2026-08-07  A. Sigdel  Created.
#
# Usage
#   scripts/run-router.sh foreground   build if needed, run until Ctrl-C
#   scripts/run-router.sh start        run detached, wait for /healthz
#   scripts/run-router.sh stop         stop a detached router
#   scripts/run-router.sh status       is one serving, and which pid
#
# deploy/bootstrap-pi.sh is the other way to start this, and it is root-only,
# aarch64 Linux and systemd. Development happens somewhere else, so until now
# the router had never run on the machine it is written on and verify-stack.sh
# had nothing to point at. That is most of how the stack drifted as far as it
# did without anyone noticing.
#
# Idempotent, as bootstrap-pi.sh is and for the same reason: the realistic use
# is running it repeatedly while something is still wrong, so every step reports
# whether its work was already done rather than failing or duplicating it.
#
# Builds without default features. The server constructs HashEmbedder in both
# places it needs one, so the onnx feature builds an embedder it has no way to
# call, at 15m13s of build against 1m25s, for code the binary cannot reach. See
# issue #87; until that is settled, this changes nothing the router does.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ROOT
readonly MANIFEST="$ROOT/router/Cargo.toml"
readonly BIN="$ROOT/router/target/release/wattrouter"
readonly STATE="$ROOT/deploy/.state"
readonly PIDFILE="$STATE/wattrouter.pid"
readonly LOGFILE="$STATE/wattrouter.log"
# Long enough for a cold start on a small board, short enough that a router
# which will never come up does not hold someone's terminal.
readonly WAIT_LIMIT=30

step() { printf '\n== %s ==\n' "$1"; }
ok() { printf '   ok    %s\n' "$1"; }
did() { printf '   done  %s\n' "$1"; }
bad() { printf '   FAIL  %s\n' "$1" >&2; }

# The address the router binds, and the base URL to reach it on.
addr() { printf '%s' "${WATTROUTER_ADDR:-127.0.0.1:8080}"; }
base() { printf 'http://%s' "$(addr)"; }

# The pid of a running router, or nothing.
#
# A pidfile naming a process that is gone is stale rather than fatal: a machine
# that lost power leaves one behind, and refusing to start until someone deletes
# it by hand is the kind of friction that gets a script abandoned.
running_pid() {
    [ -f "$PIDFILE" ] || return 1
    local pid
    pid="$(cat "$PIDFILE" 2>/dev/null)"
    [ -n "$pid" ] || return 1
    # Named rather than merely alive: a pid is reused, and killing whatever
    # inherited this one would be a great deal worse than failing to stop it.
    if ps -p "$pid" -o command= 2>/dev/null | grep -q 'wattrouter'; then
        printf '%s' "$pid"
        return 0
    fi
    return 1
}

# True IF something answers /healthz.
serving() {
    curl -fsS --max-time 2 "$(base)/healthz" >/dev/null 2>&1
}

# The credential, from the environment or from .env, never from a tracked file.
#
# .env is sourced only when the variable is unset, so an explicit value on the
# command line wins, which is what someone testing a second key expects. The
# script never writes .env; .env.example documents the names and carries no
# values.
load_credential() {
    if [ -n "${NEURALWATT_API_KEY:-}" ]; then
        ok "credential from the environment"
        return 0
    fi
    if [ -f "$ROOT/.env" ]; then
        # shellcheck disable=SC1091  # not tracked; nothing to follow at lint time
        set -a && . "$ROOT/.env" && set +a
    fi
    if [ -n "${NEURALWATT_API_KEY:-}" ]; then
        ok "credential from .env"
        return 0
    fi
    bad "NEURALWATT_API_KEY is not set"
    printf '   Obtain one at https://portal.neuralwatt.com, then either\n'
    printf '     export NEURALWATT_API_KEY=nw-...\n'
    printf '   or put it in %s/.env, which is gitignored.\n' "$ROOT"
    return 1
}

# The directory the head is read from and the trainer writes to.
#
# Created here because nothing else creates it and everything assumes it: the
# README's train-head command redirects into it, and Config::head_path reads
# from it. Note it is ~/.hermes/memory, one letter from the ~/.hermes/memories
# that Hermes itself uses for MEMORY.md. They are different directories, and the
# confusion is worth knowing about rather than tidying away.
ensure_model_cache() {
    local dir="${WATTROUTER_MODEL_CACHE:-$HOME/.hermes/memory/zeromem-models}"
    if [ -d "$dir" ]; then
        ok "model cache $dir"
    else
        mkdir -p "$dir" && did "model cache $dir"
    fi
}

# Build IF the binary is missing or older than the newest source.
build_if_stale() {
    local newest
    newest="$(find "$ROOT/router/src" "$ROOT/router/Cargo.toml" -type f -newer "$BIN" -print -quit 2>/dev/null)"
    if [ -x "$BIN" ] && [ -z "$newest" ]; then
        ok "binary is current"
        return 0
    fi
    printf '   building (no default features; see the header)\n'
    if ! cargo build --release --manifest-path "$MANIFEST" --no-default-features; then
        bad "the build failed"
        return 1
    fi
    did "built $BIN"
}

start_detached() {
    if pid="$(running_pid)" && serving; then
        ok "already serving on $(addr), pid $pid"
        return 0
    fi
    if [ -f "$PIDFILE" ] && ! running_pid >/dev/null; then
        rm -f "$PIDFILE" && did "cleared a stale pidfile"
    fi

    mkdir -p "$STATE"
    # nohup and a detached stdout, or the router dies with the shell that
    # started it, which is exactly what a `just` recipe is.
    nohup "$BIN" >>"$LOGFILE" 2>&1 &
    local pid=$!
    printf '%s' "$pid" >"$PIDFILE"

    local waited=0
    while [ "$waited" -lt "$WAIT_LIMIT" ]; do
        if serving; then
            did "serving on $(addr), pid $pid"
            return 0
        fi
        # Died rather than started slowly: say so now, with the log, instead of
        # waiting out the full limit for something that is never coming.
        if ! ps -p "$pid" >/dev/null 2>&1; then
            bad "the router exited during startup"
            tail -n 15 "$LOGFILE" >&2
            rm -f "$PIDFILE"
            return 1
        fi
        sleep 1
        waited=$((waited + 1))
    done

    bad "no answer from $(base)/healthz after ${WAIT_LIMIT}s"
    tail -n 15 "$LOGFILE" >&2
    return 1
}

stop_detached() {
    local pid
    if ! pid="$(running_pid)"; then
        rm -f "$PIDFILE"
        ok "not running"
        return 0
    fi
    kill "$pid" 2>/dev/null
    local waited=0
    while [ "$waited" -lt 10 ] && ps -p "$pid" >/dev/null 2>&1; do
        sleep 1
        waited=$((waited + 1))
    done
    if ps -p "$pid" >/dev/null 2>&1; then
        bad "pid $pid did not stop; it is holding $(addr)"
        return 1
    fi
    rm -f "$PIDFILE"
    did "stopped pid $pid"
}

report_status() {
    local pid
    if pid="$(running_pid)"; then
        if serving; then
            ok "serving on $(addr), pid $pid"
        else
            bad "pid $pid is alive but $(base)/healthz does not answer"
            return 1
        fi
    elif serving; then
        # Someone else's, or one started in the foreground. Worth distinguishing
        # from ours, because `stop` will not touch it.
        ok "something is serving on $(addr), but not from $PIDFILE"
    else
        ok "not running"
    fi
}

case "${1:-foreground}" in
    foreground)
        step "preflight"
        load_credential || exit 1
        ensure_model_cache
        build_if_stale || exit 1
        step "serving on $(addr), Ctrl-C to stop"
        exec "$BIN"
        ;;
    start)
        step "preflight"
        load_credential || exit 1
        ensure_model_cache
        build_if_stale || exit 1
        step "starting"
        start_detached || exit 1
        ;;
    stop)
        step "stopping"
        stop_detached || exit 1
        ;;
    status)
        report_status || exit 1
        ;;
    *)
        printf 'usage: %s {foreground|start|stop|status}\n' "$0" >&2
        exit 2
        ;;
esac
