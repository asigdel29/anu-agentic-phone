#!/usr/bin/env bash
# doc-tags.sh: a public async function says what it assumes about its caller.
#
# History
#   2026-08-07  A. Sigdel  Created, from a row of the standard's enforcement table
#                          that had named it for two months without it existing.
#   2026-08-08  A. Sigdel  Reads Kotlin as well, before the Android work makes it
#                          most of the codebase. Also fixed a count that reported
#                          each finding twice.
#   2026-08-09  A. Sigdel  Exits 2 when it could not run, instead of 0.
#
# Usage
#   scripts/lint/doc-tags.sh [path ...]
#
# With no arguments it reads every tracked Rust and Kotlin file. Exits 0 when
# every suspending public function documents `# Rely` and every public Rust
# function returning a `Result` documents `# Errors`, 1 when something is
# missing one, and 2 when the check could not be made at all.
#
# The third code is the point of #478. Both loops below used to read a process
# substitution, whose failure `set -euo pipefail` cannot see: awk dying wrote to
# stderr, the loop read nothing, and the script reported every file clean and
# exited 0. That happened, through a singly escaped pattern given to `-v`, and it was
# noticed by luck. "Nothing was found" and "nothing was looked at" are different
# answers and this now says which.
#
# `# Rely` is Lea's RELY: the execution context a caller must supply. The standard
# requires it on every `pub async fn` because the router holds shared mutable
# state (the embedder, the decision cache, the connection pool) and a caller
# cannot reason about a request path without knowing which operations are safe to
# interleave. That is exactly the thing a signature does not say.
#
# Kotlin's `suspend fun` is the same declaration and earns the same tag. It earns
# it harder, in fact: the Android side is a turn loop, a permission seam and a
# screen read behind a lock, which is the same count of shared mutable things
# that made this a rule for the router in the first place.
#
# It also checks `# Errors` on every public function returning a `Result`, for the
# same reason: the type says a call can fail and only the prose says when. That
# rule is Rust's alone: Kotlin has no checked exceptions and this codebase does
# not use `Result` as a return type, so there is no signature to read a failure
# off. Naming the condition is still required; it is review that keeps it, and
# saying so is better than a gate that fires on nothing.
#
# An `override` is skipped. The tag belongs on the declaration a caller reads,
# which is the interface, and repeating it on every conformance produces a wall
# of "as the interface says" that teaches people the tag is noise.
#
# `# Panics` and `# Atomic` are required by the standard and are not checked
# here. "Touches shared state" is not decidable from the text: it needs to
# know what a receiver holds and what its callees reach, and a panic can hide
# behind any callee. A gate that guessed would either miss the cases that matter
# or fail honest ones, and a wrong gate is worse than a documented gap. Review
# keeps those two, and the standard's table now says so.

set -euo pipefail

files=()
if [ "$#" -gt 0 ]; then
    files=("$@")
else
    # Through a temporary rather than a process substitution, so a git that
    # fails is an error rather than an empty list reported as a clean tree.
    listing="$(mktemp)"
    trap 'rm -f "$listing" "${report:-}"' EXIT
    if ! git ls-files -z '*.rs' '*.kt' >"$listing"; then
        printf 'doc-tags: could not list files; nothing was checked\n' >&2
        exit 2
    fi

    # Null-delimited: `mapfile` is bash 4, and the bash macOS ships is 3.2.
    while IFS= read -r -d '' path; do
        files+=("$path")
    done <"$listing"
fi

report="${report:-$(mktemp)}"
trap 'rm -f "${listing:-}" "$report"' EXIT

printf 'doc-tags: %s source file(s)\n' "${#files[@]}"

findings=0

for path in "${files[@]}"; do
    [ -f "$path" ] || continue

    # What a documentation line looks like above the item it documents. Rust
    # writes `///` and carries attributes in `#[...]`; Kotlin writes a `/** */`
    # block and carries annotations in `@...`. Walking back over the wrong set
    # stops at the first line of the comment and reports every documented item.
    #
    # Doubled backslashes because awk reads a `-v` value as a string before it is
    # used as a regex, so one level is consumed on the way in. Singly escaped,
    # `\*` arrives as a bare `*` and awk rejects the pattern, on stderr, while
    # still exiting zero, which is a lint that passes because it broke.
    case "$path" in
        *.kt)
            lang=kotlin
            doc='^[[:space:]]*(/\\*\\*|\\*|@)'
            ;;
        *)
            lang=rust
            doc='^[[:space:]]*(///|//!|#\\[)'
            ;;
    esac

    # Walk each public function back over the doc comment and attributes directly
    # above it. A blank line or any other code ends the block, which is what
    # separates one item's documentation from the item before it.
    #
    # A finding arrives as two lines (where it is, then what it wants) so only
    # the first is counted. Counting both reported twice as many items as there
    # were, which is a gate arguing with its own output.
    # Into a file, then checked. A pipe with `pipefail` would also carry the
    # status and would put the loop in a subshell, which loses `findings` on
    # every iteration, so the temporary is the option that keeps the count.
    if ! awk -v file="$path" -v lang="$lang" -v doc="$doc" '
        function documents(tag,   j, line) {
            for (j = NR - 1; j >= 1; j--) {
                line = seen[j]
                if (line ~ doc) {
                    if (index(line, tag)) return 1
                    continue
                }
                return 0
            }
            return 0
        }
        function complain(want,   text) {
            text = $0
            sub(/^[[:space:]]+/, "", text)
            printf "%s:%d  %s\n              %s\n", file, NR, substr(text, 1, 66), want
        }

        lang == "rust" && /^[[:space:]]*pub async fn / && !documents("# Rely") {
            complain("wants # Rely: what the caller must supply")
        }
        lang == "rust" && /^[[:space:]]*pub (async )?fn .*->.*Result</ && !documents("# Errors") {
            complain("wants # Errors: the condition, not just the type")
        }
        # Kotlin is public unless it says otherwise, so the visibility test is a
        # negative one. `override` is skipped: see the header.
        lang == "kotlin" && /(^|[[:space:]])suspend fun / \
            && !/(private|internal|protected|override)[[:space:]]/ \
            && !documents("# Rely") {
            complain("wants # Rely: what the caller must supply")
        }
        { seen[NR] = $0 }
    ' "$path" >"$report"; then
        printf 'doc-tags: could not read %s; nothing was checked\n' "$path" >&2
        exit 2
    fi

    while IFS= read -r finding; do
        printf '  %s\n' "$finding"
        case "$finding" in
            ' '*) ;;
            *) findings=$((findings + 1)) ;;
        esac
    done <"$report"
done

if [ "$findings" -eq 0 ]; then
    printf 'doc-tags: every public item states what a reader cannot see\n'
    exit 0
fi

cat <<EOF

$findings public item(s) missing a tag the standard requires.

Say what a signature cannot. \`# Rely\` is the context the caller must supply:
whether it may be called from the request path, whether it blocks, what it may be
interleaved with. \`# Errors\` is the condition that triggers a failure, not the
type it returns; the type is already in the signature.

  /// # Rely
  /// Called from the request path. Must not block the executor; the ONNX call is
  /// dispatched to the blocking pool.

docs/coding-standard.md has the full form.
EOF
exit 1
