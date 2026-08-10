#!/usr/bin/env bash
# test-gates.sh — the gates fail closed.
#
# History
#   2026-08-09  A. Sigdel  Created with the fix for #478.
#   2026-08-09  A. Sigdel  Covers the dependabot exemption, both ways round.
#
# Usage
#   scripts/lint/test-gates.sh
#
# Four loops read a process substitution, whose failure `set -euo pipefail`
# cannot see: the command dies, the loop reads nothing, and the script reports
# that it found nothing wrong. #324 caught one of them by luck — somebody was
# watching for a new rule to fire and it did not — and #478 found three more,
# including the guard runner, where the summary said three guards passed that
# had not run.
#
# The failure being tested for is invisible by construction, so the only proof
# is to cause it deliberately and check the exit code. That is what this does.
#
# Exit codes under test: 0 clean, 1 something was found, 2 the check could not
# be made. The third is the one that did not exist.

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly root

failures=0

# Run a command and say whether it exited with the code expected.
#
# The command is expected to fail, so it runs under `if !` rather than bare:
# `set -e` would take the script down on the first case that works.
check() {
    local what="$1" want="$2"
    shift 2

    local got=0
    "$@" >/dev/null 2>&1 || got=$?

    if [ "$got" = "$want" ]; then
        printf '  ok    %s (exit %s)\n' "$what" "$got"
    else
        printf '  FAIL  %s: wanted exit %s, got %s\n' "$what" "$want" "$got"
        failures=$((failures + 1))
    fi
}

printf 'test-gates: the gates report what they could not do\n\n'

# A directory on PATH ahead of everything, holding a command that fails. This
# is how the process substitution is made to die without editing the scripts:
# the thing they call is replaced, not the call.
broken="$(mktemp -d)"
trap 'rm -rf "$broken"' EXIT
scratch=

make_broken() {
    printf '#!/bin/sh\nexit 3\n' >"$broken/$1"
    chmod +x "$broken/$1"
}

# --- the two linters, when the command that finds their input dies ---

make_broken git
check "doc-tags with no git" 2 \
    env PATH="$broken:$PATH" bash "$root/scripts/lint/doc-tags.sh"
check "file-headers with no git" 2 \
    env PATH="$broken:$PATH" bash "$root/scripts/lint/file-headers.sh"
rm -f "$broken/git"

# --- doc-tags, when the awk that reads a file dies ---

make_broken awk
check "doc-tags with no awk" 2 \
    env PATH="$broken:$PATH" bash "$root/scripts/lint/doc-tags.sh" "$root/router/src/lib.rs"
rm -f "$broken/awk"

# --- the guard runner, when the registry will not parse ---

make_broken jq
check "run-all with no jq" 2 \
    env PATH="$broken:$PATH" bash "$root/scripts/guards/run-all.sh"
rm -f "$broken/jq"

# --- and each still finds what it is for ---
#
# Without this the four above would pass against a script that exited 2
# unconditionally, which is a gate that fails closed by never opening.

check "doc-tags on a clean file" 0 \
    bash "$root/scripts/lint/doc-tags.sh" "$root/router/src/lib.rs"

untagged="$(mktemp -d)/Untagged.kt"
cat >"$untagged" <<'KT'
// Untagged.kt — a suspending function with nothing said about its caller.
//
// History
//   2026-08-09  A. Sigdel  Created.

package com.getlora.wattrouter

/** Does a thing. */
suspend fun somethingUndocumented(): Int = 1
KT
check "doc-tags on a file missing a tag" 1 \
    bash "$root/scripts/lint/doc-tags.sh" "$untagged"

headerless="$(mktemp -d)/headerless.rs"
printf 'pub fn nothing() {}\n' >"$headerless"
check "file-headers on a file with no header" 1 \
    bash "$root/scripts/lint/file-headers.sh" "$headerless"

# --- the one guard exemption, both ways round ---
#
# #493 added it so dependabot's pull requests are not permanently red. An
# exemption nothing tests is one that either stops working or quietly widens.

check "issue-link exempts dependabot" 0 \
    env PR_AUTHOR="dependabot[bot]" PR_TITLE="bump x" PR_BODY="" \
    PR_HEAD_REF="dependabot/cargo/x" bash "$root/scripts/guards/issue-link.sh"

check "issue-link still applies to a person" 1 \
    env PR_AUTHOR="somebody" PR_TITLE="bump x" PR_BODY="" \
    PR_HEAD_REF="some-branch" bash "$root/scripts/guards/issue-link.sh"

# --- a removal is one question, and an edit beside it is not (#542) ---
#
# Built as a real repository rather than asserted against a fixture: the rule is
# about what `git diff --diff-filter=D` reports, and a test that does not run git
# is testing an assumption about git.

scratch="$(mktemp -d)"
trap 'rm -rf "$broken" "$scratch"' EXIT

# One repository per case, rather than a revert chaining them: a revert that
# fails takes the whole script down under `set -e`, and it did.
#
# $1 is where, $2 is whether the surviving file is also edited.
plant() {
    local where="$1" edit="$2"
    mkdir -p "$where"
    git -C "$where" init -q .
    git -C "$where" config user.email t@example.com
    git -C "$where" config user.name t
    # 400 lines, over the limit on its own.
    seq 1 400 >"$where/big.txt"
    printf 'one line\n' >"$where/small.txt"
    git -C "$where" add -A
    git -C "$where" commit -qm base
    git -C "$where" rm -q big.txt
    if [ "$edit" = "edit" ]; then
        seq 1 400 >>"$where/small.txt"
        git -C "$where" add -A
    fi
    git -C "$where" commit -qm change
}

# Inlined rather than wrapped in a helper, which is also what every case above
# does: `check` takes a command, and a function passed as one reads to shellcheck
# as unreachable code.
plant "$scratch/gone" plain >/dev/null 2>&1
check "a subtree leaving does not count" 0 \
    env BASE_SHA="$(git -C "$scratch/gone" rev-parse HEAD~1)" \
    HEAD_SHA="$(git -C "$scratch/gone" rev-parse HEAD)" \
    GIT_DIR="$scratch/gone/.git" GIT_WORK_TREE="$scratch/gone" \
    bash "$root/scripts/guards/pr-size.sh"

plant "$scratch/hidden" edit >/dev/null 2>&1
check "an edit beside a removal still counts" 1 \
    env BASE_SHA="$(git -C "$scratch/hidden" rev-parse HEAD~1)" \
    HEAD_SHA="$(git -C "$scratch/hidden" rev-parse HEAD)" \
    GIT_DIR="$scratch/hidden/.git" GIT_WORK_TREE="$scratch/hidden" \
    bash "$root/scripts/guards/pr-size.sh"

printf '\n'
if [ "$failures" -eq 0 ]; then
    printf 'test-gates: every gate said what it could not do\n'
    exit 0
fi

printf '%s case(s) failed.\n\n' "$failures"
cat <<'EOF'
A gate that exits 0 when it could not run is worse than one that is absent: the
summary says it passed. Read the case above and either make the script report
the failure, or change what this expects and say why in the pull request.
EOF
exit 1
