#!/usr/bin/env bash
# slopgate.sh: reject the register that arrives with unreviewed generated prose.
#
# History
#   2026-08-07  A. Sigdel  Created.
#   2026-08-10  A. Sigdel  A sixth check: the two dashes #582 removed.
#
# Reads BASE_SHA, HEAD_SHA, PR_TITLE and PR_BODY from the environment, as its
# neighbours do. Exits 0 when it finds nothing, 1 otherwise.
#
# Usage
#   BASE_SHA=... HEAD_SHA=... scripts/guards/slopgate.sh
#
# It reads three surfaces, because the standard governs prose in all three: the
# lines a diff adds, the commit messages in the range, and the pull request's own
# title and body. Removed lines are not read: a change that deletes a banned word
# should not be the change blamed for it.
#
# Every check runs even after one has found something, so a contributor sees the
# whole list in one run. That mirrors run-all.sh, and for the same reason.
#
# The words are in slopgate.patterns, which is the one file excluded from the diff
# this reads, and which explains the rule for adding one. This script is not
# excluded: it is prose about the register, so it is the last thing that should be
# exempt from a check on the register.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/guards/lib.sh
. "$here/lib.sh"

guard_range
guard_pr_text

# The word lists live beside this file rather than in it. They are the only thing
# here that must contain the words it rejects, and keeping them separate is what
# lets the guard read the rest of this script, two hundred lines of prose about
# the register, instead of exempting it.
# shellcheck source=scripts/guards/slopgate.patterns
. "$here/slopgate.patterns"

# Emoji as UTF-8 bytes, so the match does not depend on the locale grep was given.
# F0 9F covers the emoji planes; the three after it are the dingbats that turn up
# in generated checklists.
#
# Read everywhere except program source. Six emoji are already in the tree and
# every one of them is load-bearing: `WriteFileToolTests.swift:69` and its
# neighbours feed hostile Unicode through a round trip precisely to prove it
# survives. An emoji in a commit message is a register; an emoji in a test
# fixture is the test.
readonly EMOJI=$'\xf0\x9f|\xe2\x9c\x85|\xe2\x9d\x8c|\xe2\x9a\xa0'

# Program source, where a non-ASCII literal is usually data under test. Everything
# else carries prose (Markdown, YAML, unit files, shell, the justfile) and none
# of those end in `.md`, which is all an earlier version of this read. Anchored
# against the `path:line:text` shape a finding is reported in.
readonly SOURCE_LINE='^[^:]*\.(rs|swift|py):'

# Every hit from every check. The total is taken over the distinct lines in here,
# because a line two checks caught is one line to go and read, not two.
all_hits=''

# Record one check's hits. Takes them as an argument rather than on stdin, so that
# it runs in this shell and what it accumulates survives: a pipeline would put it
# in a subshell and the additions would be lost.
check() {
    local what="$1"
    local hits="$2"

    if [ -z "$hits" ]; then
        return 0
    fi

    printf '\n%s:\n' "$what"
    printf '%s\n' "$hits" | sed 's/^/  /'
    all_hits="$all_hits"$'\n'"$hits"
}

# Added lines in the range, as `path:line:text`. Three dots so that commits landing
# on the base branch after this one opened are not read as this branch's work.
#
# -U0 leaves only added and removed lines, so the new-file line number is the
# hunk's start advanced once per added line; a removed line does not advance it.
added_lines() {
    # The shared excludes, plus the word lists: those are the one thing here that
    # must contain what it rejects. `lib.sh` says why each is anchored at the root.
    git diff --unified=0 --no-color --no-renames "$base...$head" -- \
        "${GUARD_EXCLUDES[@]}" \
        ':(exclude,top)scripts/guards/slopgate.patterns' |
        awk '
            # `+++ b/path` names a file only before the first hunk of its section.
            # Inside a hunk the same three characters are an added line whose text
            # begins with two pluses, and reading that as a header renames the file
            # to the line content, restarts the numbering, and swallows the line.
            /^diff --git / {
                in_hunk = 0
                path = ""
                next
            }
            /^\+\+\+ / && in_hunk == 0 {
                path = substr($0, 5)
                sub(/^b\//, "", path)
                next
            }
            /^@@ / {
                in_hunk = 1
                match($0, /\+[0-9]+/)
                line = substr($0, RSTART + 1, RLENGTH - 1) + 0
                next
            }
            /^\+/ {
                if (path != "" && path != "/dev/null")
                    print path ":" line ":" substr($0, 2)
                line++
            }
        '
}

# Commit messages in the range, and the pull request text, as one labelled stream,
# so a finding names the surface to go and fix.
prose_lines() {
    git log --format='%h %s%n%b' "$base..$head" | sed 's/^/commit: /'
    printf '%s\n' "$title" | sed 's/^/pr title: /'
    printf '%s\n' "$body" | sed 's/^/pr body: /'
}

diff_text=$(added_lines)
prose_text=$(prose_lines)
both=$(printf '%s\n%s\n' "$diff_text" "$prose_text")

printf 'slopgate: %s added line(s), %s line(s) of prose\n' \
    "$(printf '%s' "$diff_text" | LC_ALL=C grep -ac . || true)" \
    "$(printf '%s' "$prose_text" | LC_ALL=C grep -ac . || true)"

# Every check reads with `-a` under LC_ALL=C. A diff carries whatever bytes an
# added line held, and a stream grep decides is binary is answered with a single
# "binary file matches", which `check` would then report as the finding, hiding
# every real hit in that run behind a line naming nothing.
#
# The four pattern checks read the same stream the same way, so they read it
# through one function: the binary-safety idiom above was missing from four of
# five checks once already, and a copy per check is a chance per check to miss it
# again.
over_everything() {
    printf '%s\n' "$both" | LC_ALL=C grep -aiE "$1" || true
}

check 'Marketing register: say the thing instead of describing it' \
    "$(over_everything "$REGISTER")"

check 'Chat-context shorthand: the reader was not in the conversation' \
    "$(over_everything "$SHORTHAND")"

check 'Attribution trailer: the repository has one authorial voice' \
    "$(over_everything "$ATTRIBUTION")"

# Read everywhere, including program source, which is where the emoji check draws
# its line. The difference is what the mark is doing there: a non-ASCII literal in
# a test is usually the thing under test, while a dash in a comment is prose that
# declined to say which of four jobs it was doing. There is no fixture that needs
# one.
check 'Em-dash or en-dash: use the mark that says which job it is doing' \
    "$(over_everything "$DASHES")"

# A marker naming an issue is a plan; one naming nothing is a note to somebody who
# has already left.
#
# The exclusion starts at the marker and refuses to cross a hash, so it asks
# whether the marker names an issue rather than whether the line holds a hash
# followed by a digit anywhere. A colour literal earlier in the line is not an
# issue number.
check 'Marker with no issue: name one (#123), or do the work' \
    "$(printf '%s\n' "$diff_text" | LC_ALL=C grep -aE "$MARKER" |
        LC_ALL=C grep -avE "$MARKER"'[^#]*#[0-9]+' || true)"

prose_files=$(printf '%s\n' "$diff_text" | LC_ALL=C grep -avE "$SOURCE_LINE" || true)

check 'Emoji outside source: a register, not a decision' \
    "$(printf '%s\n%s\n' "$prose_files" "$prose_text" | LC_ALL=C grep -aE "$EMOJI" || true)"

# Distinct lines: `sort -u` collapses a line two checks caught, and `grep -c .`
# counts what is left without the blank `all_hits` starts life as.
#
# The `|| true` is load-bearing under `set -o pipefail`. `grep` exits 1 when it
# matches nothing, which is the ordinary case of a clean run. Without it the
# substitution fails, `set -e` ends the script here, and a clean guard reports
# failure having printed no finding at all.
findings=$(printf '%s\n' "$all_hits" | sort -u | LC_ALL=C grep -ac . || true)

if [ "$findings" -eq 0 ]; then
    printf '\nslopgate: clean\n'
    exit 0
fi

cat <<EOF

slopgate found $findings line(s).

This guard is advisory: it reports, and the job still passes. Four of its six
checks are decidable, but the register and shorthand lists are a judgement about
prose, and a style gate that blocks a correct change on a false positive teaches
people to route around gates rather than to write more carefully.

So read each line above and decide. If a word is right where it is, keep it and
say so in review. If a list is wrong, the lists are in slopgate.patterns beside
this script and a word comes out in one line, subject to the rule stated there:
a word belongs on a list only while this repository's own prose does not use it.
EOF
exit 1
