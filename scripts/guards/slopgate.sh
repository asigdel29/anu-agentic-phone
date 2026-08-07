#!/usr/bin/env bash
# slopgate.sh — reject the register that arrives with unreviewed generated prose.
#
# History
#   2026-08-07  A. Sigdel  Created.
#
# Reads BASE_SHA, HEAD_SHA, PR_TITLE and PR_BODY from the environment, as its
# neighbours do. Exits 0 when it finds nothing, 1 otherwise.
#
# Usage
#   BASE_SHA=... HEAD_SHA=... scripts/guards/slopgate.sh
#
# It reads three surfaces, because the standard governs prose in all three: the
# lines a diff adds, the commit messages in the range, and the pull request's own
# title and body. Removed lines are not read — a change that deletes a banned word
# should not be the change blamed for it.
#
# Every check runs even after one has found something, so a contributor sees the
# whole list in one run. That mirrors run-all.sh, and for the same reason.
#
# On the lists: a word is eligible only while this repository's own prose does not
# already use it. That rule is the whole defence of the list, and applying it cost
# five candidates — `additionally`, `significant`, `notably`, `unlock` and
# `landscape` are all in files written here, so none of them can be banned. Re-run
# that check before adding a word.
#
# This file excludes itself from the diff it reads. It has to: a file defining a
# list of forbidden words necessarily contains every word on it.

set -euo pipefail

base="${BASE_SHA:-}"
head="${HEAD_SHA:-HEAD}"
title="${PR_TITLE:-}"
body="${PR_BODY:-}"

if [ -z "$base" ]; then
    printf 'BASE_SHA is not set; cannot compute a diff.\n' >&2
    exit 1
fi

# Marketing register: words that describe a thing rather than say it.
readonly REGISTER='leverages?|leveraging|utili[sz]e[sd]?|delve[sd]?|seamless(ly)?|robust|comprehensive|powerful|plethora|myriad|elevates?|boasts|testament|cutting-edge|state-of-the-art|best-in-class|meticulous(ly)?'

# Chat-context shorthand: phrases addressed to a conversation rather than to a
# reader who was not in it.
readonly SHORTHAND="it.?s worth noting|as mentioned (above|earlier)|as (we|you) (discussed|saw)|in (summary|conclusion)|deep dive|dive into|at the end of the day|needless to say|ever-evolving|in today.s|game.chang|under the hood|first-class citizen"

# Attribution, matched by shape rather than by name. The standard forbids naming a
# tool, so a guard that grepped for product names would be the first file here to
# break the rule it enforces. The robot emoji those trailers carry is left to the
# emoji check, which keeps this tree free of the character entirely.
readonly ATTRIBUTION='co-authored-by:|generated (with|by) \['

# Emoji as UTF-8 bytes, so the match does not depend on the locale grep was given.
# F0 9F covers the emoji planes; the three after it are the dingbats that turn up
# in generated checklists.
#
# Read over prose and Markdown only, never over source. Six emoji are already in
# the tree and every one of them is load-bearing: `WriteFileToolTests.swift:69`
# and its neighbours feed hostile Unicode through a round trip precisely to prove
# it survives. An emoji in a commit message is a register; an emoji in a test
# fixture is the test.
readonly EMOJI=$'\xf0\x9f|\xe2\x9c\x85|\xe2\x9d\x8c|\xe2\x9a\xa0'

findings=0

# Record one check's hits. Takes them as an argument rather than on stdin, so that
# it runs in this shell and its addition to `findings` survives — a pipeline would
# put it in a subshell and the count would be lost.
check() {
    local what="$1"
    local hits="$2"

    if [ -z "$hits" ]; then
        return 0
    fi

    printf '\n%s:\n' "$what"
    printf '%s\n' "$hits" | sed 's/^/  /'
    findings=$((findings + $(printf '%s\n' "$hits" | wc -l)))
}

# Added lines in the range, as `path:line:text`. Three dots so that commits landing
# on the base branch after this one opened are not read as this branch's work.
#
# -U0 leaves only added and removed lines, so the new-file line number is the
# hunk's start advanced once per added line; a removed line does not advance it.
added_lines() {
    git diff --unified=0 --no-color --no-renames "$base...$head" -- \
        ':(exclude)*.lock' \
        ':(exclude)**/*.lock' \
        ':(exclude)**/testdata/**' \
        ':(exclude)**/weights.json' \
        ':(exclude)scripts/guards/slopgate.sh' |
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
    "$(printf '%s' "$diff_text" | grep -c . || true)" \
    "$(printf '%s' "$prose_text" | grep -c . || true)"

check 'Marketing register — say the thing instead of describing it' \
    "$(printf '%s\n' "$both" | grep -iE "$REGISTER" || true)"

check 'Chat-context shorthand — the reader was not in the conversation' \
    "$(printf '%s\n' "$both" | grep -iE "$SHORTHAND" || true)"

check 'Attribution trailer — the repository has one authorial voice' \
    "$(printf '%s\n' "$both" | grep -iE "$ATTRIBUTION" || true)"

# A marker naming an issue is a plan; one naming nothing is a note to somebody who
# has already left.
check 'Marker with no issue — name one (#123), or do the work' \
    "$(printf '%s\n' "$diff_text" | grep -E '\b(TODO|FIXME|HACK|XXX)\b' | grep -v '#[0-9]' || true)"

markdown_text=$(printf '%s\n' "$diff_text" | grep -E '^[^:]*\.md:' || true)

check 'Emoji in prose — a register, not a decision' \
    "$(printf '%s\n%s\n' "$markdown_text" "$prose_text" | LC_ALL=C grep -aE "$EMOJI" || true)"

if [ "$findings" -eq 0 ]; then
    printf '\nslopgate: clean\n'
    exit 0
fi

cat <<EOF

slopgate found $findings line(s).

This guard is advisory: it reports, and the job still passes. Three of its five
checks are decidable, but the register and shorthand lists are a judgement about
prose, and a style gate that blocks a correct change on a false positive teaches
people to route around gates rather than to write more carefully.

So read each line above and decide. If a word is right where it is, keep it and
say so in review. If a list is wrong, the lists are at the top of this file and a
word comes out in one line — subject to the rule stated there: a word belongs on
a list only while this repository's own prose does not use it.
EOF
exit 1
