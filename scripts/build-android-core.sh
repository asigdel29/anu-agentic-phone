#!/usr/bin/env bash
# build-android-core.sh: build the routing core as a library JNI can load.
#
# History
#   2026-08-08  A. Sigdel  Created.
#   2026-08-08  A. Sigdel  Asserts the page alignment, which held by the NDK's
#                          default rather than by anything here saying so.
#
# The iOS counterpart's shape, with the three differences Android forces.
#
# A shared object rather than an archive. JNI loads a library by name at runtime;
# there is no link step of the app's own to hand a `.a` to. That is a per-build
# choice rather than a manifest one: `crate-type = [..., "cdylib"]` in
# Cargo.toml makes every target build a dynamic library, and the iOS slices then
# fail to link one they neither want nor can produce. `cargo rustc --crate-type`
# says it here, where it is true.
#
# One ABI. arm64-v8a covers every device worth shipping to and, on an Apple
# silicon machine, the emulator as well, the same reasoning that gives the iOS
# script one simulator slice. x86_64-linux-android is one entry in the loop below
# and belongs there the day somebody builds on an Intel host.
#
# API 24 as the floor for the *toolchain*, which is not the app's minSdk. The NDK
# wrapper picks the platform headers to compile the vendored C against (libgit2
# and SQLite) and choosing a low one there costs nothing and keeps the .so
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
readonly OUT="${1:-$ROOT/android/core/src/main/jniLibs}"

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
# NDK rather than the one running it: darwin-x86_64 is correct on Apple silicon.
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

# cc and ar for the vendored C (libgit2 and SQLite both build one) and the
# linker for the Rust half. Named per target, which is how cargo and cc-rs each
# find theirs.
export CC_aarch64_linux_android="$BIN/aarch64-linux-android$API-clang"
export AR_aarch64_linux_android="$BIN/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_aarch64_linux_android"

printf 'building %s\n' "$TARGET"
# git and memory because a phone has no shell
# and cannot afford the ONNX embedder, whichever phone it is. android for the JNI
# entry points, which is the half iOS does not want: it links the archive
# directly and has no JVM to be reached from.
cargo rustc --manifest-path "$MANIFEST" --target "$TARGET" \
    --release --lib --no-default-features --features git,memory,android \
    --crate-type cdylib

mkdir -p "$OUT/$ABI"
cp "$ROOT/router/target/$TARGET/release/libwattrouter.so" "$OUT/$ABI/"

# Devices shipping with Android 15 and later may use 16 KB memory pages, and a
# library laid out for 4 KB will not load on one at all. The failure is an
# UnsatisfiedLinkError naming the library and nothing else, on a class of device
# nobody here owns yet, so it would be found by somebody else.
#
# NDK r28 and later align to 16 KB by default, so this holds today by default
# rather than by intent, which is the reason to check it. An older NDK found by
# resolve_ndk on another machine would quietly undo it, and the build would look
# exactly the same.
assert_page_alignment() {
    local so="$1" want=16384
    local worst="" align

    # The Align column of every LOAD segment. p_align is the maximum page size a
    # segment is laid out for, so the smallest one decides. $NF rather than a
    # fixed column because Flg is "R E" on one line and "RW" on the next, and
    # counting fields from the left gets a different answer for each.
    #
    # The hex is converted by the shell rather than by awk: strtonum is a gawk
    # extension and the awk macOS ships is not gawk.
    while read -r align; do
        align=$((align))
        if [ -z "$worst" ] || [ "$align" -lt "$worst" ]; then
            worst="$align"
        fi
    done < <("$BIN/llvm-readelf" -l "$so" | awk '$1 == "LOAD" { print $NF }')

    if [ -z "$worst" ]; then
        printf 'could not read the LOAD segments of %s\n' "$so" >&2
        return 1
    fi
    if [ "$worst" -lt "$want" ]; then
        cat >&2 <<EOF
$so is aligned for a $worst-byte page, and Android 15 and later may use $want.

It will not load there. The toolchain decides this: NDK r28 and later align to
16 KB by default, so an older NDK is the likely cause:

  $NDK

Name a newer one in ANDROID_NDK_HOME, or pass -Wl,-z,max-page-size=$want.
EOF
        return 1
    fi
    printf '  %s-byte page alignment\n' "$worst"
}

printf '\n%s\n' "$OUT/$ABI/libwattrouter.so"
du -sh "$OUT/$ABI/libwattrouter.so" | awk '{print "  " $1}'
assert_page_alignment "$OUT/$ABI/libwattrouter.so"
