# justfile — single entry point for building, training, deploying and verifying
# the stack.
#
# History
#   2026-08-05  A. Sigdel  Created. Toolchain check only; later work adds the
#                          build, train, deploy and verify recipes alongside the
#                          scripts they invoke.
#
# Contents
#   default    List available recipes.
#   toolchain  Report whether each required tool is present and new enough.
#
# Recipes are added in the same change that adds the script they call, so
# `just --list` never advertises a recipe that cannot run.

# Directory holding the bge-small ONNX model, shared with zeromem so the ~130MB
# download happens once rather than once per process.
model_cache_dir := env_var_or_default("WATTROUTER_MODEL_CACHE", home_directory() / ".hermes/memory/zeromem-models")

# Address the router binds. Hermes points at this.
router_addr := env_var_or_default("WATTROUTER_ADDR", "127.0.0.1:8080")

# List available recipes.
default:
    @just --list

# Put this repository's plugins where Hermes finds them; enables nothing.
install-hermes:
    scripts/install-hermes.sh

# Report what pointing Hermes at the router would change. Changes nothing.
hermes-config:
    scripts/apply-hermes-config.sh

# Point Hermes at the router. Reversible with `just hermes-unconfig`.
hermes-config-apply:
    scripts/apply-hermes-config.sh --apply

# Put the Hermes configuration back, leaf by leaf.
hermes-unconfig:
    scripts/apply-hermes-config.sh --revert

# Run the router in the foreground until Ctrl-C.
router:
    scripts/run-router.sh foreground

# Start the router detached, and wait until it answers.
up:
    scripts/run-router.sh start

# Stop a detached router.
down:
    scripts/run-router.sh stop

# Is a router serving, and which process is it.
status:
    scripts/run-router.sh status

# Build the routing core as an xcframework Swift can link. Needs Xcode.
ios-core:
    scripts/build-ios-core.sh

# Generate ios/WattRouter.xcodeproj from ios/project.yml. Safe to re-run; the
# project is build output and gitignored, so this is how it comes into being on a
# fresh clone as well as how it is refreshed after editing the spec.
ios-project:
    cd ios && xcodegen generate

# Run the Swift tests on the shared simulator. Needs `just ios-core` first.
ios-test:
    scripts/test-ios.sh

# Build the routing core as a library JNI can load. Needs the Android NDK; the
# script says how to get one if there is none.
android-core:
    scripts/build-android-core.sh

# Build the Android library, core first. The core is cargo's to build and Gradle
# only packages it, so the order is not a convenience: an AAR assembled without
# it installs and fails to load.
android:
    #!/usr/bin/env bash
    set -euo pipefail

    if ! command -v gradle >/dev/null 2>&1; then
        cat >&2 <<'EOF'
    gradle is not installed, and this project has no wrapper — a wrapper is a jar,
    and this repository does not track binaries it cannot review. Install it with:

      brew install gradle
    EOF
        exit 1
    fi

    scripts/build-android-core.sh >/dev/null
    # Gradle wants the SDK by environment or by a local.properties, and
    # local.properties is a machine-specific file somebody would commit.
    cd android && ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}" \
        gradle assembleDebug --console=plain

# Run the Kotlin tests. On the JVM, so no emulator and no core build: what they
# check is the request a turn becomes, which touches nothing Android.
android-test:
    cd android && ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}" \
        gradle test --console=plain

# Check the stack end to end. Needs a router; `just up` first.
verify:
    scripts/verify-stack.sh {{ "http://" + router_addr }}

# Run the pull request guards over this branch, before there is a pull request.
#
# Pass the base when the pull request will not target the default branch, which
# is how a change that ran over 300 lines and split into a follow-up is reviewed:
#   just guards 153-agents-md
guards base="":
    #!/usr/bin/env bash
    # The same scripts pr-governance.yml runs, with the same inputs, so what this
    # reports is what the job will report. There is no pull request text to pass:
    # `issue-link` falls back to the branch name, which is the convention here
    # anyway, and `slopgate` still reads the commit messages in the range.
    set -euo pipefail

    base="{{ base }}"
    if [ -z "$base" ]; then
        base="$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null || echo origin/main)"
    fi

    if ! git rev-parse --verify --quiet "$base" >/dev/null; then
        printf 'No such base: %s\n' "$base" >&2
        exit 1
    fi

    # The merge base rather than the base tip, matching what CI passes: commits
    # landing on the base after this branch opened are not this branch's work.
    printf 'guards: %s...%s\n' "$base" "$(git branch --show-current)"
    BASE_SHA="$(git merge-base "$base" HEAD)" \
    HEAD_SHA="$(git rev-parse HEAD)" \
    PR_HEAD_REF="$(git branch --show-current)" \
        scripts/guards/run-all.sh

# Report the toolchain; exit non-zero if anything is missing or too old.
toolchain:
    #!/usr/bin/env bash
    # Doubles as the precondition check for the recipes added later, which is why
    # it fails rather than warns: a missing toolchain should stop the caller here
    # and not midway through a build.
    set -uo pipefail
    missing=0

    # Each entry is "binary|purpose". Version floors are checked below only where
    # a specific floor actually matters; otherwise presence is enough.
    for entry in \
        "cargo|build the router" \
        "rustc|build the router" \
        "uv|manage the training environment" \
        "python3|run the training scripts" \
        "git|version control" \
        "gh|issues and pull requests" \
        "curl|verification scripts"
    do
        bin="${entry%%|*}"; purpose="${entry##*|}"
        if command -v "$bin" >/dev/null 2>&1; then
            printf '  present  %-10s %s\n' "$bin" "$purpose"
        else
            printf '  MISSING  %-10s %s\n' "$bin" "$purpose"
            missing=1
        fi
    done

    # Android is optional and reported rather than required: the board and the
    # phone build without it, and `just toolchain` failing over a milestone
    # somebody is not working on is a check people learn to ignore.
    for entry in \
        "ndk|build the core for Android|${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk}" \
        "sdk|build the Android library|${ANDROID_HOME:-$HOME/Library/Android/sdk}/platforms"
    do
        what="${entry%%|*}"; rest="${entry#*|}"
        why="${rest%%|*}"; where="${rest##*|}"
        if [ -d "$where" ]; then
            printf '  present  %-10s %s\n' "$what" "$why"
        else
            printf '  absent   %-10s %s (optional)\n' "$what" "$why"
        fi
    done
    if command -v gradle >/dev/null 2>&1; then
        printf '  present  %-10s %s\n' "gradle" "build the Android library"
    else
        printf '  absent   %-10s %s (optional)\n' "gradle" "build the Android library"
    fi

    # Python 3.11+ is Hermes's floor. Checked explicitly because an older
    # interpreter fails deep inside an install rather than here.
    if command -v python3 >/dev/null 2>&1; then
        if ! python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)'; then
            echo "  TOO OLD  python3    need 3.11+ (Hermes floor)"
            missing=1
        fi
    fi

    echo
    if [ "$missing" -eq 0 ]; then
        echo "toolchain OK"
    else
        echo "toolchain incomplete — install the entries marked MISSING or TOO OLD" >&2
    fi
    exit "$missing"
