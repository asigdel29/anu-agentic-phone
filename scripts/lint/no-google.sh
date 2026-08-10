#!/usr/bin/env bash
# no-google.sh: no Play Services in the resolved dependency graph.
#
# History
#   2026-08-10  A. Sigdel  Created, as the first buildable step of #603.
#
# Usage
#   scripts/lint/no-google.sh
#
# Exits 0 when the graph is clean, 1 when it is not, 2 when it could not be
# resolved. The third is the one that matters: a check that cannot run must not
# read as a check that passed.
#
# `AndroidWhereabouts.kt:7` declines play-services-location and takes
# LocationManager with FUSED_PROVIDER instead, and nothing kept that decision
# after the pull request that made it. docs/decisions/an-agentic-android.md says
# why it is worth keeping: every dependency here is a thing that has to exist on
# whatever phone this ends up running on.
#
# It reads the RESOLVED GRAPH, not the source. That is the whole design, and a
# grep would be wrong rather than merely coarse. Seven lines in this repository
# name com.google.android.* and every one is legitimate: `ScreenToolsTest.kt`,
# `BarredTest.kt` and `CopiedTest.kt` name Gmail, Maps and the permission
# controller as OTHER APPS THE AGENT DRIVES, `Barred.kt` and `themes.xml` say the
# words in prose, and `settings.gradle.kts` declares `google()` as a Maven
# repository, which is where AndroidX comes from and is not Play Services.
#
# A transitive arrival is also the case worth catching, and the case a grep
# cannot see at all: nothing in this repository would name the coordinate.
#
# Both modules, not just :app. `:app` depends on `project(":core")`, so a
# coordinate entering through the library would appear in `:app`'s graph too, but
# a build that packaged `:core` alone would not be covered by asking about `:app`,
# and the point of `:core` being a library is that something else can link it.
#
# releaseRuntimeClasspath because that is what ships. A debug-only dependency is
# a different argument and this does not make it.
#
# This is the one lint the `lint` job does not call. That job has no Java, no
# Gradle and no SDK; the `android` job has all three. Running it where it cannot
# resolve would mean exiting 2 on every run, which is a check nobody keeps.

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly root

# Play Services and its neighbours, as coordinate fragments.
#
# `com.google.android.gms` is Play Services proper. `play-services` catches the
# artefact half of the same coordinate. `firebase` arrives under
# com.google.firebase rather than com.google.android and pulls gms behind it.
# `com.google.android.play` is the app-update and integrity family, which is
# separate from gms and requires it just the same.
#
# AndroidX is deliberately absent. It is androidx.* and ships in the APK; it is
# not Play Services and does not need one on the phone.
readonly BANNED='com\.google\.android\.gms|play-services|firebase|com\.google\.android\.play'

# One module's resolved runtime graph, or nothing and a non-zero status.
#
# # Arguments
# * `module`: a Gradle project path, WHERE it exists in settings.gradle.kts.
#
# # Returns
# The dependency tree on stdout. Empty with status 1 IF Gradle could not resolve.
resolved() {
    local module="$1"

    (cd "$root/android" && gradle "$module:dependencies" \
        --configuration releaseRuntimeClasspath --console=plain -q 2>/dev/null)
}

findings=0

for module in :app :core; do
    graph=""
    if ! graph="$(resolved "$module")" || [ -z "$graph" ]; then
        printf 'no-google: could not resolve %s; nothing was checked\n' "$module" >&2
        printf 'no-google: needs Gradle, a JDK and an Android SDK. Run just toolchain.\n' >&2
        exit 2
    fi

    # -a under LC_ALL=C for the reason slopgate states: a stream grep decides is
    # binary answers with one line naming nothing, which would hide every hit.
    hits="$(printf '%s\n' "$graph" | LC_ALL=C grep -aE "$BANNED" || true)"

    if [ -n "$hits" ]; then
        printf '\n%s releaseRuntimeClasspath:\n' "$module"
        printf '%s\n' "$hits" | sed 's/^/  /'
        findings=$((findings + $(printf '%s\n' "$hits" | LC_ALL=C grep -ac .)))
    fi
done

if [ "$findings" -eq 0 ]; then
    printf 'no-google: no Play Services in either module\n'
    exit 0
fi

cat <<EOF

no-google found $findings line(s).

A Play Services dependency is a thing that has to exist on whatever phone this
runs on, and docs/decisions/an-agentic-android.md is about phones where it does
not. Every one taken so far has had a platform answer: LocationManager with
FUSED_PROVIDER rather than FusedLocationProviderClient, and #603 has the rest.

If there is no platform answer, that is a decision rather than a dependency, and
it belongs in a pull request body before it belongs in a build file.
EOF
exit 1
