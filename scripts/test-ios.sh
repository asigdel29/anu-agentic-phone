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
# Usage
#   scripts/build-ios-core.sh && scripts/test-ios.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ROOT
readonly UDID=4C1C5104-238E-448C-8490-1157310AF5E2
readonly LOCK=/tmp/ios-sim.lock
readonly DERIVED="$ROOT/ios/.build/DerivedData"
readonly WAIT_LIMIT=120

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
