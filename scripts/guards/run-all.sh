#!/usr/bin/env bash
# run-all.sh: run every guard in registry.json and report as one result.
#
# History
#   2026-08-05  A. Sigdel  Created.
#   2026-08-09  A. Sigdel  Exits 2 when the registry will not parse, instead of
#                          reporting that every guard passed.
#
# Runs all guards even after one fails, so a contributor sees everything wrong in
# a single run rather than discovering the next problem on the next push. Only
# guards marked `hard` in the registry affect the exit code.
#
# The guard list comes through a file rather than a process substitution, and
# #478 says why this one mattered most of the four that did it. A registry with
# a trailing comma made jq exit non-zero, the loop below run zero times, and
# this print "All hard guards passed" over three guards that had not run, on
# the gate that decides whether a pull request may merge.
#
# Usage
#   PR_TITLE=... PR_BODY=... PR_HEAD_REF=... BASE_SHA=... scripts/guards/run-all.sh

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
registry="$here/registry.json"

if ! command -v jq >/dev/null 2>&1; then
    printf 'jq is required to read %s\n' "$registry" >&2
    exit 1
fi

# Read once and checked, before anything is reported.
listing="$(mktemp)"
trap 'rm -f "$listing"' EXIT
if ! jq -r '.guards[] | [.id, .script, .mode, .description] | @tsv' \
    "$registry" >"$listing"; then
    printf 'could not read %s; no guard was run\n' "$registry" >&2
    exit 2
fi

failed_hard=0
failed_advisory=0

while IFS=$'\t' read -r id script mode description; do
    printf '\n=== %s (%s) ===\n%s\n\n' "$id" "$mode" "$description"

    if bash "$here/$script"; then
        printf -- '-> pass\n'
        continue
    fi

    if [ "$mode" = "hard" ]; then
        printf -- '-> FAIL (hard)\n'
        failed_hard=$((failed_hard + 1))
    else
        printf -- '-> fail (advisory, not blocking)\n'
        failed_advisory=$((failed_advisory + 1))
    fi
done <"$listing"

printf '\n=== summary ===\n'
printf 'hard failures:      %s\n' "$failed_hard"
printf 'advisory failures:  %s\n' "$failed_advisory"

if [ "$failed_hard" -gt 0 ]; then
    printf '\nOne or more hard guards failed.\n'
    exit 1
fi

printf '\nAll hard guards passed.\n'
