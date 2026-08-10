#!/usr/bin/env bash
# issue-link.sh: require that a pull request references an issue.
#
# History
#   2026-08-05  A. Sigdel  Created.
#   2026-08-09  A. Sigdel  Exempts dependabot, which cannot open an issue first.
#
# Reads PR_TITLE, PR_BODY, PR_HEAD_REF and PR_AUTHOR from the environment; the
# workflow supplies them, and so can a developer running this by hand. Exits 0
# when a reference is found, 1 otherwise.
#
# Accepts a reference anywhere in the body, title or branch name. GitHub only
# closes an issue for a keyword in the body, but the rule here is traceability
# rather than automatic closure, so `Refs #12` on a follow-up counts just as much
# as `Closes #12` on the change that finishes the work.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/guards/lib.sh
. "$here/lib.sh"

guard_pr_text
branch="${PR_HEAD_REF:-}"

# The one exemption, and it is narrow on purpose. A bot cannot open an issue
# first, and inventing one per dependency bump is worse than not having the
# rule: it would be an issue whose whole content is the pull request beside it.
#
# Only this guard. pr-size still applies and should: it already excludes
# lockfiles, so a Cargo bump is a few lines of a manifest, and a dependency
# change touching 300 lines of source is exactly one somebody should read.
if [ "${PR_AUTHOR:-}" = "dependabot[bot]" ]; then
    printf 'dependabot: exempt, see the header\n'
    exit 0
fi

# `#12` in the title or body, or a leading `12-` in the branch name, which is the
# shape `gh issue develop` and the convention here both produce.
if printf '%s\n%s' "$title" "$body" | grep -qE '#[0-9]+'; then
    printf 'issue referenced in the title or body\n'
    exit 0
fi

if printf '%s' "$branch" | grep -qE '^[0-9]+-'; then
    printf 'issue referenced in the branch name (%s)\n' "$branch"
    exit 0
fi

cat <<'EOF'
No issue reference found.

Every pull request is associated with an issue, so that the reason for a change
is recorded somewhere other than the diff.

Add one of:
  - `Closes #12` in the pull request body, for work that finishes the issue.
  - `Refs #12`, for work that advances it without finishing it.
  - A branch named `12-short-description`.

If no issue exists yet, open one first. The issue is where the problem is stated;
the pull request is only where it is solved.
EOF
exit 1
