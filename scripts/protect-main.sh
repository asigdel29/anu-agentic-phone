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
# Two calls, not one, and the second is easy to miss from the name. A ruleset
# says what may reach `main`; it cannot say what the merge button offers or what
# happens to a branch afterwards. Those are repository settings, so a repository
# that forbade merge commits on `main` went on offering them in the UI for as
# long as only the ruleset was written down.
#
# Needs admin on the repository. Without it the API answers 403 and this says so
# rather than reporting success over nothing.

set -euo pipefail

REPO="${REPO:-asigdel29/anu-agentic-phone}"
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

# Trunk-based development, which is three settings the ruleset cannot express.
#
# delete_branch_on_merge because a branch outlives its pull request for no
# reason and 192 of them had accumulated before this was written. The two
# allow_* falses because the ruleset already forbids both on `main`, and a rule
# stated twice in two places is a rule that will disagree with itself.
#
# allow_update_branch is the one that is not obviously trunk discipline and is.
# The ruleset sets strict_required_status_checks_policy, so a branch behind
# `main` cannot merge; without this there is no button to bring it forward, and
# the way people work around that is by not keeping branches short.
settings() {
    cat <<'JSON'
{
  "delete_branch_on_merge": true,
  "allow_merge_commit": false,
  "allow_rebase_merge": false,
  "allow_squash_merge": true,
  "allow_update_branch": true
}
JSON
}

existing="$(gh api "repos/$REPO/rulesets" --jq \
    ".[] | select(.name == \"$NAME\") | .id" 2>/dev/null || true)"

if [ -n "$dry" ]; then
    printf 'would %s the ruleset on %s/%s:\n\n' \
        "$([ -n "$existing" ] && echo update || echo create)" "$REPO" "$BRANCH"
    rules
    printf '\nwould set on %s:\n\n' "$REPO"
    settings
    exit 0
fi

if [ -n "$existing" ]; then
    rules | gh api --method PUT "repos/$REPO/rulesets/$existing" --input - >/dev/null
    printf 'updated ruleset %s\n' "$existing"
else
    rules | gh api --method POST "repos/$REPO/rulesets" --input - >/dev/null
    printf 'created the ruleset\n'
fi

settings | gh api --method PATCH "repos/$REPO" --input - >/dev/null
printf 'set the repository settings\n'

printf '\n=== %s now requires ===\n' "$BRANCH"
gh api "repos/$REPO/rules/branches/$BRANCH" --jq '.[] | .type' | sort -u | sed 's/^/  /'
printf '\nchecks:\n'
gh api "repos/$REPO/rules/branches/$BRANCH" \
    --jq '.[] | select(.type == "required_status_checks")
          | .parameters.required_status_checks[].context' | sed 's/^/  /'

# Read back rather than echoed. The PATCH above answers with the repository it
# believes it wrote, and an option the API silently declined would be reported
# here as set.
printf '\nthe repository now allows:\n'
gh api "repos/$REPO" --jq '
    "  squash        \(.allow_squash_merge)",
    "  merge commit  \(.allow_merge_commit)",
    "  rebase        \(.allow_rebase_merge)",
    "  update branch \(.allow_update_branch)",
    "  delete branch on merge  \(.delete_branch_on_merge)"'
