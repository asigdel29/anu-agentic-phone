#!/usr/bin/env bash
# pr-size.sh — cap a pull request at 300 changed lines.
#
# History
#   2026-08-05  A. Sigdel  Created.
#
# Reads BASE_SHA and HEAD_SHA from the environment. Exits 0 when the diff is
# within the limit, 1 otherwise.
#
# Lockfiles and vendored data are excluded: they are generated, nobody reads them
# line by line, and counting them would push changes over the limit for reasons
# unrelated to how much there is to review.

set -euo pipefail

readonly LIMIT=300

base="${BASE_SHA:-}"
head="${HEAD_SHA:-HEAD}"

if [ -z "$base" ]; then
    printf 'BASE_SHA is not set; cannot compute a diff.\n' >&2
    exit 1
fi

# Three dots: measure against the merge base, so that commits landing on the base
# branch after this one opened do not count towards its size.
stat=$(git diff --numstat "$base...$head" -- \
    ':(exclude)*.lock' \
    ':(exclude)**/*.lock' \
    ':(exclude)**/testdata/**' \
    ':(exclude)**/weights.json')

total=$(printf '%s' "$stat" | awk '{a += $1; d += $2} END {print a + d + 0}')

printf 'changed lines: %s (limit %s)\n' "$total" "$LIMIT"

if [ "$total" -le "$LIMIT" ]; then
    exit 0
fi

printf '\n%s\n' "$stat" | sort -rn | head -10
cat <<EOF

This pull request changes $total lines, over the limit of $LIMIT.

The limit exists because review quality falls off a cliff with size, not because
the work is wrong. Split it:

  1. Decide which part of the change stands on its own and leave that here.
  2. Open a new issue for the remainder, linking back to this one as its parent.
  3. Branch from this branch, move the remainder there, and open a second pull
     request based on this one.

The second pull request's diff then shows only the remainder, and both stay
reviewable. Reviewing the pair in order reads the same as reviewing one large
change, without any reviewer having to hold 600 lines in their head at once.
EOF
exit 1
