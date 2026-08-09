#!/usr/bin/env bash
# protect-main.sh — what may reach the default branch.
#
# History
#   2026-08-09  A. Sigdel  Created.
#
# Usage
#   scripts/protect-main.sh [--dry-run]
#
# Applies the ruleset docs/decisions/what-guards-main.md argues for, and prints
# what is there afterwards. Idempotent: run it again to see the current state,
# which is why there is no separate "show" mode.
#
# A script rather than a settings page, for the reason every other gate here is
# a file: a rule nobody can read in a diff is a rule nobody reviews, and the one
# below has a clause whose absence is the whole point.
#
# Needs admin on the repository. Without it the API answers 403 and this says so
# rather than reporting success over nothing.

set -euo pipefail

REPO="${REPO:-asigdel29/anu-agentic-stack}"
BRANCH="${BRANCH:-main}"
NAME="main is reached through a pull request"

dry=""
[ "${1:-}" = "--dry-run" ] && dry=1

# The two checks are DISPLAY names — the `name:` field of each job, not its id.
# `required` and `guards` are the ids, and a ruleset naming those waits forever
# for a check that never reports under that name.
#
# There is deliberately no merge queue here, and it is not an omission.
# pr-governance.yml triggers on pull_request alone; on a merge-group ref there
# is no pull request to read, so Guards would never report and a queue would
# stall on every candidate. what-guards-main.md says so at more length, because
# enabling one is the tidy-looking change somebody makes later.
rules() {
    cat <<'JSON'
{
  "name": "main is reached through a pull request",
  "target": "branch",
  "enforcement": "active",
  "conditions": {
    "ref_name": { "include": ["~DEFAULT_BRANCH"], "exclude": [] }
  },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "required_linear_history" },
    {
      "type": "pull_request",
      "parameters": {
        "required_approving_review_count": 0,
        "dismiss_stale_reviews_on_push": false,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": false,
        "allowed_merge_methods": ["squash"]
      }
    },
    {
      "type": "required_status_checks",
      "parameters": {
        "strict_required_status_checks_policy": true,
        "do_not_enforce_on_create": false,
        "required_status_checks": [
          { "context": "Required" },
          { "context": "Guards" }
        ]
      }
    }
  ]
}
JSON
}

existing="$(gh api "repos/$REPO/rulesets" --jq \
    ".[] | select(.name == \"$NAME\") | .id" 2>/dev/null || true)"

if [ -n "$dry" ]; then
    printf 'would %s the ruleset on %s/%s:\n\n' \
        "$([ -n "$existing" ] && echo update || echo create)" "$REPO" "$BRANCH"
    rules
    exit 0
fi

if [ -n "$existing" ]; then
    rules | gh api --method PUT "repos/$REPO/rulesets/$existing" --input - >/dev/null
    printf 'updated ruleset %s\n' "$existing"
else
    rules | gh api --method POST "repos/$REPO/rulesets" --input - >/dev/null
    printf 'created the ruleset\n'
fi

printf '\n=== %s now requires ===\n' "$BRANCH"
gh api "repos/$REPO/rules/branches/$BRANCH" --jq '.[] | .type' | sort -u | sed 's/^/  /'
printf '\nchecks:\n'
gh api "repos/$REPO/rules/branches/$BRANCH" \
    --jq '.[] | select(.type == "required_status_checks")
          | .parameters.required_status_checks[].context' | sed 's/^/  /'
