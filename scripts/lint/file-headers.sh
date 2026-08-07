#!/usr/bin/env bash
# file-headers.sh — every source file opens by saying what it is and when it changed.
#
# History
#   2026-08-07  A. Sigdel  Created, from a row of the standard's enforcement table
#                          that had named it for two months without it existing.
#
# Usage
#   scripts/lint/file-headers.sh [path ...]
#
# With no arguments it reads every tracked source file. Exits 0 when all of them
# carry a header, 1 otherwise, listing what is missing from which.
#
# The header shape differs by language and the difference is not cosmetic. Rust
# uses `//!` because a module doc is an item attribute; Swift and shell use `//`
# and `#`; Python uses a module docstring, and it does not repeat the filename
# because the module is already named by the import that reaches it. A check
# demanding one shape would be wrong about three of them, so each is read on its
# own terms and only the two things that must be true everywhere are required:
# the file says what it is, and it says when it changed.

set -euo pipefail

# Read the opening comment block, whatever a comment looks like here.
#
# # Arguments
# * `path` — WHERE the file exists and is readable.
#
# # Returns
# The header text, or empty when the file opens with something else.
header_of() {
    local path="$1"

    case "$path" in
        *.py)
            # From the first line of the module docstring to its close. `-v` so a
            # one-line docstring does not end the block on its own opening quotes.
            awk '
                NR == 1 && /^#!/ { next }
                !started && /^[[:space:]]*("""|'"'''"')/ { started = 1; print; next }
                started {
                    print
                    if (/("""|'"'''"')[[:space:]]*$/) exit
                }
                !started && NF { exit }
            ' "$path"
            ;;
        *)
            # Leading comment lines, blank lines included, up to the first line of
            # code. A shebang is skipped rather than treated as the header.
            awk '
                NR == 1 && /^#!/ { next }
                /^[[:space:]]*(\/\/|#|\*|\/\*)/ { print; next }
                /^[[:space:]]*$/ { print; next }
                { exit }
            ' "$path"
            ;;
    esac
}

failed=0

# Report one file's missing pieces, and count it once however many it is missing.
report() {
    local path="$1" what="$2"
    printf '  %-56s %s\n' "$path" "$what"
    failed=$((failed + 1))
}

# A null-delimited read loop rather than `mapfile`: this has to run on the machine
# it is written on as well as in CI, and the bash macOS ships is 3.2, where
# `mapfile` does not exist. The nulls also keep a filename containing whitespace
# from splitting into two, which is why ci.yml passes -z to its own file lists.
files=()
if [ "$#" -gt 0 ]; then
    files=("$@")
else
    while IFS= read -r -d '' path; do
        files+=("$path")
    done < <(git ls-files -z '*.rs' '*.py' '*.swift' '*.sh')
fi

printf 'file-headers: %s source file(s)\n' "${#files[@]}"

for path in "${files[@]}"; do
    [ -f "$path" ] || continue
    header="$(header_of "$path")"

    if [ -z "${header//[[:space:]]/}" ]; then
        report "$path" 'no opening comment block'
        continue
    fi

    missing=""

    # Python names its module through the import path, so the docstring does not
    # repeat the filename. Every other language here writes `name.ext — purpose`.
    case "$path" in
        *.py) ;;
        *)
            if ! printf '%s' "$header" | head -4 | grep -qF "$(basename "$path")"; then
                missing="names the file"
            fi
            ;;
    esac

    if ! printf '%s' "$header" | grep -q 'History'; then
        missing="${missing:+$missing, }has a History block"
    fi

    if [ -n "$missing" ]; then
        report "$path" "header does not: $missing"
    fi
done

if [ "$failed" -eq 0 ]; then
    printf 'file-headers: every file carries one\n'
    exit 0
fi

cat <<EOF

$failed file(s) without a usable header.

A header says what the file is for and when it changed, so a reader can decide
whether to open it. docs/coding-standard.md gives the shape; the neighbouring
files give it more usefully. The two things checked here are the two that have
to be true in every language: the file says what it is, and it carries History.
EOF
exit 1
