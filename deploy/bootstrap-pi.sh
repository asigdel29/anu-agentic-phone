#!/usr/bin/env bash
# bootstrap-pi.sh — install the stack on a fresh aarch64 board.
#
# History
#   2026-08-05  A. Sigdel  Created.
#
# Usage
#   sudo NEURALWATT_API_KEY=nw-... deploy/bootstrap-pi.sh
#
# Idempotent by construction. The realistic use is running this repeatedly while
# something is still wrong, so every step checks whether its work is already done
# and says so rather than failing or duplicating it. A script that only works on a
# clean machine is a script nobody trusts to re-run.
#
# What it does NOT do: install Hermes or zeromem. Hermes has its own installer
# that changes independently of this repository, and zeromem needs a wheel built
# for the target. Both are documented in the README rather than wrapped here,
# because wrapping an installer that moves is how a bootstrap script goes stale.

set -euo pipefail

readonly USER_NAME=hermes
readonly BIN=/usr/local/bin/wattrouter
readonly ENV_FILE=/etc/wattrouter.env
readonly STATE_DIR=/var/lib/wattrouter
readonly UNIT_DIR=/etc/systemd/system
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

step() { printf '\n== %s ==\n' "$1"; }
ok() { printf '   ok    %s\n' "$1"; }
did() { printf '   done  %s\n' "$1"; }

if [ "$(id -u)" -ne 0 ]; then
    echo "must run as root: sudo $0" >&2
    exit 1
fi

case "$(uname -m)" in
    aarch64 | arm64) ;;
    *)
        echo "warning: this targets aarch64; found $(uname -m). Continuing." >&2
        ;;
esac

step "service account"
if id "$USER_NAME" >/dev/null 2>&1; then
    ok "user $USER_NAME exists"
else
    # System account with no login shell: it runs a service and nothing else.
    useradd --system --create-home --shell /usr/sbin/nologin "$USER_NAME"
    did "created $USER_NAME"
fi

step "state directory"
if [ -d "$STATE_DIR" ]; then
    ok "$STATE_DIR exists"
else
    mkdir -p "$STATE_DIR"
    did "created $STATE_DIR"
fi
chown -R "$USER_NAME:$USER_NAME" "$STATE_DIR"

step "credential"
if [ -s "$ENV_FILE" ] && grep -q '^NEURALWATT_API_KEY=.' "$ENV_FILE"; then
    ok "$ENV_FILE already carries a key (left untouched)"
elif [ -n "${NEURALWATT_API_KEY:-}" ]; then
    # Written before the mode is set would leave a readable window, so create it
    # empty, restrict it, then fill it.
    install -m 600 /dev/null "$ENV_FILE"
    printf 'NEURALWATT_API_KEY=%s\nWATTROUTER_ADDR=127.0.0.1:8080\nWATTROUTER_MODEL_CACHE=%s\n' \
        "$NEURALWATT_API_KEY" "$STATE_DIR" >"$ENV_FILE"
    did "wrote $ENV_FILE (mode 600)"
else
    echo "   FAIL  no key: set NEURALWATT_API_KEY, or write $ENV_FILE yourself" >&2
    exit 1
fi

step "binary"
if [ -x "$here/router/target/aarch64-unknown-linux-gnu/release/wattrouter" ]; then
    src="$here/router/target/aarch64-unknown-linux-gnu/release/wattrouter"
elif [ -x "$here/router/target/release/wattrouter" ]; then
    src="$here/router/target/release/wattrouter"
else
    echo "   FAIL  no release binary; build it first:" >&2
    echo "         cargo build --release --manifest-path router/Cargo.toml" >&2
    exit 1
fi
# Compare before copying so a re-run over an unchanged binary does not restart a
# healthy service for nothing.
if cmp -s "$src" "$BIN" 2>/dev/null; then
    ok "$BIN is current"
else
    install -m 755 "$src" "$BIN"
    did "installed $BIN from ${src#"$here"/}"
fi

step "systemd units"
changed=0
for unit in wattrouter hermes; do
    file="$here/deploy/systemd/$unit.service"
    [ -f "$file" ] || continue
    if cmp -s "$file" "$UNIT_DIR/$unit.service" 2>/dev/null; then
        ok "$unit.service is current"
    else
        install -m 644 "$file" "$UNIT_DIR/$unit.service"
        did "installed $unit.service"
        changed=1
    fi
done
if [ "$changed" -eq 1 ]; then
    systemctl daemon-reload
    did "reloaded systemd"
fi

step "start the router"
systemctl enable wattrouter >/dev/null 2>&1 || true
systemctl restart wattrouter
for _ in $(seq 1 30); do
    if curl -fsS http://127.0.0.1:8080/healthz >/dev/null 2>&1; then
        ok "router is serving"
        break
    fi
    sleep 1
done
if ! curl -fsS http://127.0.0.1:8080/healthz >/dev/null 2>&1; then
    echo "   FAIL  router did not become healthy in 30s" >&2
    echo "         journalctl -u wattrouter -n 50 --no-pager" >&2
    exit 1
fi

cat <<'DONE'

Router installed and serving. Still to do by hand, and deliberately not scripted:

  1. Hermes      — its installer changes independently of this repository.
                   Then copy hermes/config.yaml to $HERMES_HOME/config.yaml and
                   symlink hermes/plugins/model-providers/neuralwatt into
                   $HERMES_HOME/plugins/model-providers/.
  2. zeromem     — needs a wheel built for this board. See the README.
  3. OpenCode    — copy opencode/opencode.jsonc to ~/.config/opencode/.

Then check the whole stack:

  NEURALWATT_API_KEY=... scripts/verify-stack.sh
DONE
