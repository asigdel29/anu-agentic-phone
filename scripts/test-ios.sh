#!/usr/bin/env bash
# test-ios.sh — run the Swift tests on the shared iPhone simulator.
#
# History
#   2026-08-06  A. Sigdel  Created.
#
# The simulator is shared with another agent, so this takes a lock before booting
# and hands it back afterwards, including on failure: a run that fails still frees
# the device, and leaving it held is worse than the failure. The lock records the
# owning pid, so a stale one from a killed run is recognisable rather than
# deadlocking whoever comes next.
#
# The device is found by name and created when it is absent. A UDID is generated
# when a device is made, so one written down here names a device on the machine
# that made it and on no other; this script used to carry one, and it worked
# exactly as long as it never left that machine. A name is chosen rather than
# generated, so it is the same identity everywhere — which is also what the lock
# needs, since two agents sharing a device have to agree on which device.
#
# Usage
#   scripts/build-ios-core.sh && scripts/test-ios.sh
#
# Environment
#   WATTROUTER_SIM_NAME  Device to use, created if missing. Default below.
#   WATTROUTER_SIM_UDID  An exact device, used as-is and never created.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ROOT
readonly LOCK=/tmp/ios-sim.lock
readonly DERIVED="$ROOT/ios/.build/DerivedData"
readonly WAIT_LIMIT=120

# Named for this repository so it cannot collide with a device somebody made for
# their own work, and so the lock's "the shared device" is unambiguous.
readonly SIM_NAME="${WATTROUTER_SIM_NAME:-wattrouter-tests}"

# The UDID of the named device, creating it if there is none.
#
# Reads simctl's JSON rather than its table: the table's columns move between
# Xcode versions and its device names contain spaces, so parsing it is a bug
# waiting for an upgrade.
#
# # Returns
# A UDID on stdout.
#
# # Errors
# Exits 1 IF no iOS runtime is installed, naming what to install.
resolve_device() {
    if [ -n "${WATTROUTER_SIM_UDID:-}" ]; then
        printf '%s' "$WATTROUTER_SIM_UDID"
        return 0
    fi

    local existing
    existing="$(xcrun simctl list devices --json |
        python3 -c '
import json, sys
name = sys.argv[1]
for runtime, devices in json.load(sys.stdin)["devices"].items():
    if "iOS" not in runtime:
        continue
    for d in devices:
        if d["name"] == name and d.get("isAvailable"):
            print(d["udid"])
            raise SystemExit
' "$SIM_NAME")"

    if [ -n "$existing" ]; then
        printf '%s' "$existing"
        return 0
    fi

    # Newest iOS runtime, and an iPhone that runtime supports. Newest rather than
    # a pinned version so a fresh Xcode works without an edit here; the tests do
    # not depend on a particular iOS beyond the package's floor.
    local choice
    choice="$(xcrun simctl list --json |
        python3 -c '
import json, sys

catalog = json.load(sys.stdin)
runtimes = [r for r in catalog["runtimes"]
            if r.get("isAvailable") and r["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS")]
if not runtimes:
    raise SystemExit(1)

# A version sorts wrongly as text ("17.10" < "17.9"), so compare it as numbers.
def order(runtime):
    return [int(part) for part in runtime["version"].split(".") if part.isdigit()]

newest = max(runtimes, key=order)
supported = {d["identifier"] for d in catalog["devicetypes"]}
usable = [d for d in catalog["devicetypes"]
          if d["identifier"] in supported and "iPhone" in d["name"]]
if not usable:
    raise SystemExit(1)

print(newest["identifier"])
print(usable[-1]["identifier"])
')" || {
        cat >&2 <<'EOF'
No iOS simulator runtime is installed, so there is nothing to create a device on.

A fresh Xcode ships without one. Install it with:

  xcodebuild -downloadPlatform iOS

or from Xcode, Settings then Components. Then run this again.
EOF
        exit 1
    }

    local runtime devicetype udid
    runtime="$(printf '%s' "$choice" | sed -n 1p)"
    devicetype="$(printf '%s' "$choice" | sed -n 2p)"

    printf 'creating simulator %s\n' "$SIM_NAME" >&2
    udid="$(xcrun simctl create "$SIM_NAME" "$devicetype" "$runtime")"
    printf '%s' "$udid"
}

UDID="$(resolve_device)"
readonly UDID

if [ -z "$UDID" ]; then
    printf 'could not resolve a simulator to run on\n' >&2
    exit 1
fi

if xcrun simctl list devices booted | grep -q "$UDID"; then
    printf 'simulator is already booted; another agent may be driving it\n' >&2
    exit 2
fi

waited=0
until mkdir "$LOCK" 2>/dev/null; do
    if [ "$waited" -ge "$WAIT_LIMIT" ]; then
        printf 'lock held %ss by pid %s; giving up\n' \
            "$waited" "$(cat "$LOCK/pid" 2>/dev/null || echo unknown)" >&2
        exit 2
    fi
    sleep 10
    waited=$((waited + 10))
done
echo $$ >"$LOCK/pid"

cleanup() {
    xcrun simctl shutdown "$UDID" 2>/dev/null || true
    rm -rf "$LOCK"
}
trap cleanup EXIT

# The framework's header is precompiled into a module cache. Rebuilding the core
# changes that header's timestamp without invalidating the cache, and xcodebuild
# then fails rather than rebuilding it, with a message naming neither. Drop the
# cache when the framework is the newer of the two.
if [ "$ROOT/ios/WattRouterFFI.xcframework" -nt "$DERIVED" ]; then
    rm -rf "$DERIVED"
fi

xcrun simctl boot "$UDID"
xcrun simctl bootstatus "$UDID" -b >/dev/null

log="$(mktemp)"
cd "$ROOT/ios"

# Piping xcodebuild straight into grep discards its exit status, which is how a
# failing suite reads as a passing script. The log decides; grep only formats.
if xcodebuild test -scheme WattRouter \
    -destination "platform=iOS Simulator,id=$UDID" \
    -derivedDataPath "$DERIVED" >"$log" 2>&1; then
    grep -E "Test Case .*passed|Executed [0-9]+ test" "$log" | sort -u
else
    grep -E "error:|Test Case .*failed|\*\* TEST" "$log" | tail -30
    printf '\nfull log: %s\n' "$log" >&2
    exit 1
fi
