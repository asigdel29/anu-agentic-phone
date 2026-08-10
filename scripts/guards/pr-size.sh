#!/usr/bin/env bash
# pr-size.sh: cap a pull request at 300 changed lines.
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
#
# A file deleted outright is excluded too, and #542 argues why. The limit exists
# because review quality falls off with size, which is a claim about lines
# somebody has to read. A removed subtree is not sixteen thousand things to
# review; it is one question -- should this go -- and the answer does not get
# harder as the subtree grows. Counting it made "remove a feature" inexpressible,
# and a repository that cannot delete things accretes.
#
# A file that still exists counts in full, added and removed. That keeps the
# protection worth keeping: a pull request that removes a subtree and quietly
# edits something that stays cannot hide the edit behind the removal.

set -euo pipefail

readonly LIMIT=300

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/guards/lib.sh
. "$here/lib.sh"

guard_range

# Three dots: measure against the merge base, so that commits landing on the base
# branch after this one opened do not count towards its size.
stat=$(git diff --numstat "$base...$head" -- "${GUARD_EXCLUDES[@]}")

# Which paths left entirely. --diff-filter=D is the whole of the rule: a file
# that still exists is not in this list, however much of it changed.
gone=$(git diff --name-only --diff-filter=D "$base...$head" -- "${GUARD_EXCLUDES[@]}")

# Into a file and read from one, rather than a process substitution: scripts/lint
# /test-gates.sh exists because that construction hides its command's failure
# from `set -e`, and this guard reporting a small diff because git died is the
# same class of mistake it was written to catch.
removed=$(mktemp)
trap 'rm -f "$removed"' EXIT
printf '%s\n' "$gone" >"$removed"

total=$(printf '%s' "$stat" | awk -v removed="$removed" '
    BEGIN {
        while ((getline path < removed) > 0) {
            if (path != "") deleted[path] = 1
        }
    }
    # $3 is the path. Binary files report - for both counts and add nothing.
    !($3 in deleted) { a += $1; d += $2 }
    END { print a + d + 0 }
')

# Said rather than left implicit: a total that ignores a 16,000-line removal is
# surprising unless the reason is on the screen beside it.
if [ -n "$gone" ]; then
    printf 'files removed entirely (not counted): %s\n' "$(printf '%s\n' "$gone" | grep -c .)"
fi

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
