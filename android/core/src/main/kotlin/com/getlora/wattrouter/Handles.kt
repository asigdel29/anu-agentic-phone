// Handles.kt: a handle as the model reads and writes one.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Contents
//   encode  A handle as a token.
//   decode  A token back, or nothing.
//
// The token carries the whole recipe rather than naming one held in a table,
// and the table is what this rejects. A per-reading map from "3" to a handle is
// three characters instead of thirty and it has a hole: the generation moves
// only when the shape moves, so a scrolled list keeps its generation, and a
// table looked up in the current reading answers 3 with whatever scrolled into
// row three. Closing that means keying the table by which read it came from,
// keeping several readings alive, and deciding when to drop them: three pieces
// of state to get right inside a service the system restarts.
//
// Carrying the recipe needs none of it, and every safety property already
// exists: resolve re-finds it against a fresh tree, a scrolled list refuses
// because the text matches no row (#405), and a restart refuses on the epoch.
//
// It also buys back a column. The text is in the token, so a line listing what
// is on screen does not have to print it twice.

package com.getlora.wattrouter

/** What a token starts with, so a line of prose is never read as one. */
private const val MARK = "h:"

/** Between fields. Escaped where it appears inside one. */
private const val BETWEEN = '|'

private const val ESCAPE = '\\'

/** A handle as one token: `h:send|button|Send||0`. */
fun encode(handle: Handle): String = MARK + listOf(
    handle.viewId.orEmpty(),
    handle.role,
    handle.text.orEmpty(),
    handle.description.orEmpty(),
    handle.siblingIndex.toString(),
).joinToString(BETWEEN.toString()) { field ->
    field.map { if (it == ESCAPE || it == BETWEEN) "$ESCAPE$it" else "$it" }.joinToString("")
}

/**
 * A token back into a handle.
 *
 * @return null for anything that is not one this build wrote: a missing mark,
 *   the wrong number of fields, an index that is not a number. Strict on
 *   purpose: a handle assembled from a malformed token with defaults filled in
 *   would go on to resolve against something.
 */
fun decode(token: String?): Handle? {
    val body = token?.trim()?.removePrefix(MARK)?.takeIf { token.trim().startsWith(MARK) }
        ?: return null

    val fields = split(body) ?: return null
    if (fields.size != FIELDS) return null

    val siblingIndex = fields[4].toIntOrNull() ?: return null
    if (siblingIndex < 0) return null

    return Handle(
        viewId = fields[0].takeIf { it.isNotEmpty() },
        role = fields[1],
        text = fields[2].takeIf { it.isNotEmpty() },
        description = fields[3].takeIf { it.isNotEmpty() },
        siblingIndex = siblingIndex,
    )
}

/**
 * Fields, honouring the escape.
 *
 * @return null when the body ends mid-escape, which is a token that was cut
 *   rather than one to read the last field of.
 */
private fun split(body: String): List<String>? {
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var escaped = false

    for (character in body) {
        when {
            escaped -> {
                field.append(character)
                escaped = false
            }
            character == ESCAPE -> escaped = true
            character == BETWEEN -> {
                fields += field.toString()
                field.clear()
            }
            else -> field.append(character)
        }
    }
    if (escaped) return null

    fields += field.toString()
    return fields
}

/** How many a token has. A shorter one was cut and a longer one is not ours. */
private const val FIELDS = 5
