#!/usr/bin/env bash
# build-ios-core.sh — build the routing core as an xcframework Swift can link.
#
# History
#   2026-08-06  A. Sigdel  Created.
#
# Two slices, because the phone and the simulator are different targets and a
# library built for one will not link against the other. An xcframework is the
# one packaging that carries both and lets the build pick, so neither the app
# project nor a developer has to.
#
# --no-default-features: the app calls the decision core and never the server, so
# the ONNX embedder it would otherwise drag in is 114 MB of archive for code
# nothing reaches.
#
# Usage
#   scripts/build-ios-core.sh [output-dir]

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ROOT
readonly MANIFEST="$ROOT/router/Cargo.toml"
readonly HEADERS="$ROOT/router/include"
readonly OUT="${1:-$ROOT/ios/WattRouterFFI.xcframework}"

readonly DEVICE=aarch64-apple-ios
# arm64 only, so the simulator slice serves Apple silicon and not an Intel Mac.
# Adding x86_64-apple-ios would double this build for a host nobody here has —
# the visible cost is that `-destination 'generic/platform=iOS Simulator'` fails
# to link, because generic means both architectures. Name the device instead.
readonly SIMULATOR=aarch64-apple-ios-sim

# Matches the platform floor in ios/Package.swift. Without it cargo builds
# against whichever SDK is installed and the linker warns once per object file
# that the library is newer than what is linking it — around thirty lines of
# noise that hide anything real in the same output.
export IPHONEOS_DEPLOYMENT_TARGET=17.0

for target in "$DEVICE" "$SIMULATOR"; do
    if ! rustup target list --installed | grep -qx "$target"; then
        printf 'installing rust target %s\n' "$target"
        rustup target add "$target"
    fi
    printf 'building %s\n' "$target"
    cargo build --manifest-path "$MANIFEST" --target "$target" \
        --release --lib --no-default-features
done

# Rebuilt rather than merged into: xcodebuild refuses to write over an existing
# xcframework, and a stale slice inside one is invisible until something fails to
# link for reasons that point nowhere near here.
rm -rf "$OUT"

xcodebuild -create-xcframework \
    -library "$ROOT/router/target/$DEVICE/release/libwattrouter.a" -headers "$HEADERS" \
    -library "$ROOT/router/target/$SIMULATOR/release/libwattrouter.a" -headers "$HEADERS" \
    -output "$OUT" >/dev/null

printf '\n%s\n' "$OUT"
du -sh "$OUT" | awk '{print "  " $1}'
