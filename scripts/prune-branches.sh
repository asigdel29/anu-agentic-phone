#!/usr/bin/env bash
# prune-branches.sh — remote branches whose pull request is over.
#
# History
#   2026-08-10  A. Sigdel  Created with #562.
#
# Usage
#   scripts/prune-branches.sh            # list what would go
#   scripts/prune-branches.sh --delete   # actually delete it
#
# delete_branch_on_merge stops this happening again; it does nothing about the
# 192 branches that accumulated before it was set. This is the one-off, kept
# because a one-off that is not written down is run from somebody's shell
# history with a flag they half remember.
#
# It asks the pull request rather than the history, and that is the whole of the
# difficulty. `git merge-base --is-ancestor` is the obvious test and it is wrong
# here: every merge is a squash, a squash writes a new commit, and the branch tip
# it came from is not an ancestor of anything afterwards. Asked that way, 190 of
# 193 finished branches look unmerged.
#
# So a branch goes only when a pull request says its work is over. Three states,
# and the third is why this is not one line of `xargs`:
#
#   merged or closed  its pull request is finished, so the branch is
#   open              somebody is working on it
#   no pull request   somebody pushed and has not opened one yet
#
# The last is the case that makes deleting by exclusion dangerous, and it is
# reported rather than deleted. A branch nobody opened a pull request for is
# either five minutes old or abandoned, and this cannot tell which.
#
# Dry by default, as protect-main.sh is: the destructive half needs a flag, and
# the safe half is what runs when somebody forgets one.

set -euo pipefail

REMOTE="${REMOTE:-origin}"

doit=""
[ "${1:-}" = "--delete" ] && doit=1

command -v gh >/dev/null || {
    printf 'prune-branches: needs gh\n' >&2
    exit 2
}

# Every pull request this repository has, head branch and state. Fetched once:
# the alternative is a call per branch, and there are 193 of them.
#
# --limit is above the count on purpose and checked below. A page boundary
# silently truncating this list would make finished branches look like branches
# with no pull request, which is the one state that is never deleted -- so it
# fails safe, and it still has to be noticed.
readonly PAGE=1000
pulls="$(gh pr list --state all --limit "$PAGE" --json headRefName,state \
    --jq '.[] | "\(.headRefName)\t\(.state)"')"

if [ "$(printf '%s\n' "$pulls" | grep -c .)" -ge "$PAGE" ]; then
    printf 'prune-branches: more than %s pull requests; raise PAGE\n' "$PAGE" >&2
    exit 2
fi

# The default branch by name rather than assumed, so a repository that renames
# it does not have its trunk offered up here. Read in three steps because the
# one-line form does not do what it reads as: in `a | b || echo main` the
# fallback hangs off the pipeline, whose status is sed's, and sed succeeds on
# empty input. The name to fall back to has to be chosen after the substitution,
# not beside it.
trunk="$(git symbolic-ref --quiet --short "refs/remotes/$REMOTE/HEAD" || true)"
trunk="${trunk#"$REMOTE/"}"
trunk="${trunk:-main}"

over=0
live=0
unknown=0

# The branches, from for-each-ref rather than from `git branch -r`, because the
# short name of refs/remotes/$REMOTE/HEAD is `origin` and not `origin/HEAD`: git
# resolves a bare remote name through that ref, so shortening drops the suffix
# entirely. Filtering short names for the string HEAD therefore keeps the symref
# and offers it up as a branch named after the remote. strip=3 removes exactly
# refs/remotes/<remote>, which also leaves the slashes inside a name alone --
# every dependabot branch has three of them.
while read -r branch; do
    [ -n "$branch" ] || continue
    [ "$branch" = "$trunk" ] && continue

    state="$(printf '%s\n' "$pulls" | awk -F'\t' -v b="$branch" '$1 == b { print $2 }' | head -1)"

    case "$state" in
        MERGED | CLOSED)
            over=$((over + 1))
            if [ -n "$doit" ]; then
                git push --quiet "$REMOTE" --delete "$branch" && printf 'deleted  %s\n' "$branch"
            else
                printf 'would delete  %-50s (%s)\n' "$branch" "$state"
            fi
            ;;
        OPEN)
            live=$((live + 1))
            ;;
        *)
            unknown=$((unknown + 1))
            printf 'kept          %-50s (no pull request)\n' "$branch"
            ;;
    esac
done < <(git for-each-ref --format='%(refname:strip=3)' "refs/remotes/$REMOTE/" |
    grep -vxF HEAD)

printf '\n%s finished, %s open, %s with no pull request\n' "$over" "$live" "$unknown"
[ -n "$doit" ] || printf 'nothing was deleted. Pass --delete.\n'
