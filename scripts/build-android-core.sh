#!/usr/bin/env bash
# build-android-core.sh — build the routing core as a library JNI can load.
#
# History
#   2026-08-08  A. Sigdel  Created.
#
# The iOS counterpart's shape, with the three differences Android forces.
#
# A shared object rather than an archive. JNI loads a library by name at runtime;
# there is no link step of the app's own to hand a `.a` to. That is a per-build
# choice rather than a manifest one — `crate-type = [..., "cdylib"]` in
# Cargo.toml makes every target build a dynamic library, and the iOS slices then
# fail to link one they neither want nor can produce. `cargo rustc --crate-type`
# says it here, where it is true.
#
# One ABI. arm64-v8a covers every device worth shipping to and, on an Apple
# silicon machine, the emulator as well — the same reasoning that gives the iOS
# script one simulator slice. x86_64-linux-android is one entry in the loop below
# and belongs there the day somebody builds on an Intel host.
#
# API 24 as the floor for the *toolchain*, which is not the app's minSdk. The NDK
# wrapper picks the platform headers to compile the vendored C against — libgit2
# and SQLite — and choosing a low one there costs nothing and keeps the .so
# loadable wherever the app is allowed to install. What the app targets is
# Gradle's to say, and #229 has the constraints that decide it.
#
# Usage
#   scripts/build-android-core.sh [output-dir]
#
# Environment
#   ANDROID_NDK_HOME  The NDK. Otherwise the newest under the default SDK path.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ROOT
readonly MANIFEST="$ROOT/router/Cargo.toml"
readonly OUT="${1:-$ROOT/android/jniLibs}"

# arm64-v8a is Android's name for it; the Rust target and the directory Gradle
# looks in disagree, and both are needed.
readonly TARGET=aarch64-linux-android
readonly ABI=arm64-v8a
readonly API=24

# The newest NDK installed, unless one is named. Newest rather than pinned so a
# fresh install works without an edit here, on the same reasoning as the iOS
# script's choice of simulator runtime.
resolve_ndk() {
    if [ -n "${ANDROID_NDK_HOME:-}" ]; then
        printf '%s' "$ANDROID_NDK_HOME"
        return 0
    fi
    local root="${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk"
    [ -d "$root" ] || return 1
    # Version-sorted: "29.0.9" must not beat "29.0.14". `find` at depth one
    # rather than `ls`, so a directory name with a space in it stays one name.
    local newest
    newest="$(find "$root" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | sort -V | tail -1)"
    [ -n "$newest" ] || return 1
    printf '%s' "$root/$newest"
}

if ! NDK="$(resolve_ndk)"; then
    cat >&2 <<'EOF'
No Android NDK found.

Install the command line tools and an NDK:

  brew install --cask temurin android-commandlinetools
  sdkmanager --sdk_root="$HOME/Library/Android/sdk" "ndk;29.0.14206865"

The second prints Google's licence and waits for you to accept it. Set
ANDROID_NDK_HOME to use one installed somewhere else.
EOF
    exit 1
fi
readonly NDK

# One prebuilt directory per host, and it is named for the host that built the
# NDK rather than the one running it — darwin-x86_64 is correct on Apple silicon.
BIN="$(echo "$NDK"/toolchains/llvm/prebuilt/*/bin)"
readonly BIN
if [ ! -x "$BIN/llvm-ar" ]; then
    printf 'no usable toolchain under %s\n' "$NDK" >&2
    exit 1
fi

if ! rustup target list --installed | grep -qx "$TARGET"; then
    printf 'installing rust target %s\n' "$TARGET"
    rustup target add "$TARGET"
fi

# cc and ar for the vendored C — libgit2 and SQLite both build one — and the
# linker for the Rust half. Named per target, which is how cargo and cc-rs each
# find theirs.
export CC_aarch64_linux_android="$BIN/aarch64-linux-android$API-clang"
export AR_aarch64_linux_android="$BIN/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_aarch64_linux_android"

printf 'building %s\n' "$TARGET"
# git and memory for the reason build-ios-core.sh has them: a phone has no shell
# and cannot afford the ONNX embedder, whichever phone it is. android for the JNI
# entry points, which is the half iOS does not want — it links the archive
# directly and has no JVM to be reached from.
cargo rustc --manifest-path "$MANIFEST" --target "$TARGET" \
    --release --lib --no-default-features --features git,memory,android \
    --crate-type cdylib

mkdir -p "$OUT/$ABI"
cp "$ROOT/router/target/$TARGET/release/libwattrouter.so" "$OUT/$ABI/"

printf '\n%s\n' "$OUT/$ABI/libwattrouter.so"
du -sh "$OUT/$ABI/libwattrouter.so" | awk '{print "  " $1}'
