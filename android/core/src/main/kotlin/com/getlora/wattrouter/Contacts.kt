// Contacts.kt: looking somebody up, and how little to hand over.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Contents
//   Person        Somebody in the address book.
//   Directory     Where people come from, as a seam.
//   ContactsTool  What the model calls.
//
// There is no tool here that lists everybody, and that is the decision rather
// than an argument shape. One call answering "the address book" would put every
// name, number and address somebody has into a request to a model, for a
// question that was almost certainly about one person. So a name is required, a
// blank one is refused, and what comes back is capped.
//
// The cap is said aloud. A model that cannot tell a full result from a truncated
// one answers as though it read all of them, which is the same failure the
// calendar's window has and the same fix.

package com.getlora.wattrouter

/** Somebody in the address book. */
data class Person(
    val name: String,
    /** As stored, not normalised: a number somebody wrote is one they can read. */
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
)

/** Where people come from. */
interface Directory {
    /**
     * People whose name matches, best first.
     *
     * # Rely
     * Called from the turn loop with the capability already obtained. Reads a
     * content provider, so it blocks and belongs off the main thread, and the
     * conformance moves it there rather than the caller.
     *
     * @param name what to match on, already known to be non-blank.
     * @param most the largest answer worth building.
     */
    suspend fun find(name: String, most: Int): List<Person>
}

/** Look somebody up. */
class ContactsTool(
    private val directory: Directory,
    private val permission: Permission,
) : Tool {
    override val name = "find_contact"

    override val purpose =
        "Look somebody up in the person's contacts to get their number or email " +
            "address. Give the name, or as much of it as you were told; a first " +
            "name on its own is fine. There is no way to list everybody, so ask " +
            "about the person you actually need."

    override val schema = """
        {"type":"object","properties":{"name":{"type":"string",
        "description":"The name to look for, whole or partial."}},"required":["name"]}
    """.trimIndent().replace("\n", "")

    /** # Rely
     *  Obtains CONTACTS, so it may put a dialog on screen and wait for somebody
     *  to answer it. Everything before that point runs without one. */
    override suspend fun run(arguments: String): String {
        val wanted = Tools.field(arguments, "name").trim()
        // Blank refused rather than treated as everybody. A provider handed an
        // empty pattern matches every row, which is the one call this tool
        // exists to not have.
        if (wanted.isEmpty()) return "no name was given, so nobody was looked up"

        // Arguments first, dialog second: a malformed call that spent a prompt
        // teaches somebody to refuse the next one.
        try {
            permission.obtain(Capability.CONTACTS)
        } catch (e: PermissionError) {
            // Returned, not thrown: ToolBox's catch-all would blame the
            // arguments for a decision a person made.
            return e.message.orEmpty()
        }

        return describe(wanted, directory.find(wanted, LIMIT + 1))
    }

    companion object {
        /** Most people shown. Past a handful this is the address book again. */
        const val LIMIT = 5

        /** People as the model reads them. Separate from [run] so the rendering,
         *  which is all of the decisions, is exercised without a provider. */
        fun describe(wanted: String, found: List<Person>): String {
            // Named back. "Nothing found" leaves a model unsure whether it
            // asked wrongly or asked about somebody who is not there.
            if (found.isEmpty()) return "nobody in contacts matches \"$wanted\""

            val shown = found.take(LIMIT).joinToString("\n") { person ->
                // One entry per person, however many ways there are to reach
                // them. Split by number, the same name appears twice and the
                // model reports two people.
                val ways = (person.phones + person.emails).filter { it.isNotBlank() }
                if (ways.isEmpty()) {
                    // Still shown, and said. An entry that vanishes reads as
                    // somebody who is not in the address book at all.
                    "${person.name}  (no number or address stored)"
                } else {
                    "${person.name}  ${ways.joinToString("  ")}"
                }
            }

            val rest = found.size - LIMIT
            return if (rest > 0) {
                "$shown\nand at least $rest more match; ask about one of them by name"
            } else {
                shown
            }
        }
    }
}
