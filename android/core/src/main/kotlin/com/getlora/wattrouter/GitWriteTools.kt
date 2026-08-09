// GitWriteTools.kt — staging something, and committing it.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Contents
//   GitAddTool     Stage paths.
//   GitCommitTool  Commit what is staged.
//
// One file because the pair is one move: nothing is worth staging that is not
// about to be committed, and a model shown them apart stages and forgets.
//
// Neither obtains a capability, and that is worth saying because every tool
// written since the calendar has opened by obtaining one. A repository is the
// app's own directory — there is no dialog to spend and no ordering to keep.
//
// Both answer with the status afterwards rather than with "done". A model that
// cannot see what landed does it again, which is the same reasoning
// RememberTool gives for saying back what it stored.

package com.getlora.wattrouter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The short id a commit answers with, or the words explaining why there is
 * none.
 *
 * Its own read rather than [GitStatus.from]'s: the payload is a string where
 * that one is an object, and a type parameter over two call sites would be a
 * generic decoder written for the second of them.
 */
internal fun committed(envelope: String?): String {
    if (envelope == null) return "the commit could not be attempted at all"
    Tools.field(envelope, "error").takeIf { it.isNotEmpty() }?.let { return it }

    val id = Tools.field(envelope, "ok")
    // Said back with the id. "Committed" alone leaves a model unable to
    // reference what it just wrote, and it will read the log to find out.
    return if (id.isEmpty()) "the repository answered nothing readable" else "committed $id"
}

/**
 * The paths the model named.
 *
 * @return the list, or null if `paths` was absent or not an array of strings —
 *   which is a different answer from an empty one, and gets different words.
 */
internal fun stagedPaths(arguments: String): List<String>? = runCatching {
    // jsonPrimitive throws on an element that is an object or an array, which
    // is the case worth refusing: a nested structure where a path belongs is
    // not a path the model can be told is missing.
    Json.parseToJsonElement(arguments).jsonObject["paths"]?.jsonArray
        ?.map { it.jsonPrimitive.content }
}.getOrNull()

/** Stage paths. */
class GitAddTool(private val repository: Worktree) : Tool {
    override val name = "stage_paths"

    override val purpose =
        "Stage files in the repository so the next commit includes them. Name a " +
            "directory to stage what is under it. Answers with the state of the " +
            "repository afterwards, so read that rather than assuming."

    override val schema = """
        {"type":"object","properties":{"paths":{"type":"array","items":{"type":"string"},
        "description":"Paths relative to the repository root. A directory stages what is in it."}},
        "required":["paths"]}
    """.trimIndent().replace("\n", "")

    /** # Rely
     *  Nothing. The repository is the app's own directory, so there is no
     *  capability to obtain and no dialog this can put on screen. */
    override suspend fun run(arguments: String): String {
        val paths = stagedPaths(arguments)
            ?: return "paths must be a list of strings, such as [\"src/Main.kt\", \"docs\"]"
        // Distinct from a malformed call. Staging nothing is a call that did
        // what it said; saying so keeps the model from reading the message
        // above as a complaint about the arguments it wrote correctly.
        if (paths.isEmpty()) return "no paths were named, so nothing was staged"

        return GitStatusTool.answer(GitStatus.from(repository.add(paths)))
    }
}

/** Commit what is staged. */
class GitCommitTool(private val repository: Worktree) : Tool {
    override val name = "commit"

    override val purpose =
        "Commit what is currently staged. Stage first: this commits the index " +
            "and nothing else, and committing nothing is refused rather than " +
            "quietly writing an empty commit."

    override val schema = """
        {"type":"object","properties":{"message":{"type":"string",
        "description":"The commit message. One imperative line, then a blank line, then why."}},
        "required":["message"]}
    """.trimIndent().replace("\n", "")

    /** # Rely
     *  Nothing, as [GitAddTool]. */
    override suspend fun run(arguments: String): String {
        val message = Tools.field(arguments, "message").trim()
        // Refused here rather than at the core, which would take it: git allows
        // an empty message and a history of them is a history nobody can read.
        if (message.isEmpty()) return "a commit needs a message, so nothing was committed"

        // Committing nothing is refused inside the core, and deliberately: it
        // writes a commit whose tree matches its parent without complaint, and
        // a model doing that in a loop believes it is making progress. This
        // renders that refusal rather than second-guessing it.
        return committed(repository.commit(message))
    }
}
