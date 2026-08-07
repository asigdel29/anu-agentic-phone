#!/usr/bin/env bash
# doc-tags.sh — a public async function says what it assumes about its caller.
#
# History
#   2026-08-07  A. Sigdel  Created, from a row of the standard's enforcement table
#                          that had named it for two months without it existing.
#
# Usage
#   scripts/lint/doc-tags.sh [path ...]
#
# With no arguments it reads every tracked Rust file. Exits 0 when every
# `pub async fn` documents `# Rely` and every public function returning a
# `Result` documents `# Errors`, 1 otherwise.
#
# `# Rely` is Lea's RELY: the execution context a caller must supply. The standard
# requires it on every `pub async fn` because the router holds shared mutable
# state — the embedder, the decision cache, the connection pool — and a caller
# cannot reason about a request path without knowing which operations are safe to
# interleave. That is exactly the thing a signature does not say.
#
# It also checks `# Errors` on every public function returning a `Result`, for the
# same reason: the type says a call can fail and only the prose says when.
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
    # Null-delimited: `mapfile` is bash 4, and the bash macOS ships is 3.2.
    while IFS= read -r -d '' path; do
        files+=("$path")
    done < <(git ls-files -z '*.rs')
fi

printf 'doc-tags: %s Rust file(s)\n' "${#files[@]}"

findings=0

for path in "${files[@]}"; do
    [ -f "$path" ] || continue

    # Walk each public function back over the doc comment and attributes directly
    # above it. A blank line or any other code ends the block, which is what
    # separates one item's documentation from the item before it.
    while IFS= read -r finding; do
        printf '  %s\n' "$finding"
        findings=$((findings + 1))
    done < <(awk -v file="$path" '
        function documents(tag,   j, line) {
            for (j = NR - 1; j >= 1; j--) {
                line = seen[j]
                if (line ~ /^[[:space:]]*(\/\/\/|\/\/!|#\[)/) {
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

        /^[[:space:]]*pub async fn / && !documents("# Rely") {
            complain("wants # Rely: what the caller must supply")
        }
        /^[[:space:]]*pub (async )?fn .*->.*Result</ && !documents("# Errors") {
            complain("wants # Errors: the condition, not just the type")
        }
        { seen[NR] = $0 }
    ' "$path")
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
type it returns — the type is already in the signature.

  /// # Rely
  /// Called from the request path. Must not block the executor; the ONNX call is
  /// dispatched to the blocking pool.

docs/coding-standard.md has the full form.
EOF
exit 1
