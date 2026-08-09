// GitStatus.kt — what the repository answered, as values and as lines.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Contents
//   GitHead        Where HEAD points.
//   GitChange      What happened to one path.
//   GitStatus      The working tree, decoded and rendered.
//   GitStatusTool  What the model calls.
//
// Recollection.kt decodes the same envelope and throws the error text away,
// which is right there: a store that could not be searched has nothing useful
// to add. It is wrong here. "Not a repository", a tree that could not be walked
// and a path that is not there are different problems with different answers,
// and a model told only that something failed makes the same call again.
//
// The rendering is GitStatusTool.swift's, arrived at deliberately rather than
// re-derived. Its decisions carry their reasons at the lines that make them.

package com.getlora.wattrouter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where `HEAD` points. */
sealed interface GitHead {
    /** On a branch, with at least one commit. */
    data class Branch(val name: String) : GitHead

    /** On a commit rather than a branch, named by its short id. */
    data class Detached(val commit: String) : GitHead

    /** On a branch that does not exist yet: what `git init` leaves behind. */
    data class Unborn(val name: String) : GitHead
}

/** What happened to one path. */
data class GitChange(val path: String, val kind: String)

/** The working tree, against the index and the head. */
data class GitStatus(
    val head: GitHead?,
    val staged: List<GitChange> = emptyList(),
    val unstaged: List<GitChange> = emptyList(),
    val untracked: List<String> = emptyList(),
    val conflicted: List<String> = emptyList(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Read an envelope into a status, or into the words that explain it.
         *
         * @return the status, or the error's own message. Null only when there
         *   was no envelope at all — the runtime failing to allocate, which is
         *   not something the core said and not something to quote.
         */
        fun from(envelope: String?): Result<GitStatus>? {
            val body = envelope
                ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
                ?: return null

            body["error"]?.jsonPrimitive?.contentOrNull?.let {
                return Result.failure(IllegalStateException(it))
            }
            val ok = body["ok"]?.jsonObject ?: return null

            return Result.success(
                GitStatus(
                    head = headOf(ok["head"]),
                    staged = changesIn(ok["staged"]),
                    unstaged = changesIn(ok["unstaged"]),
                    untracked = pathsIn(ok["untracked"]),
                    conflicted = pathsIn(ok["conflicted"]),
                ),
            )
        }

        private fun headOf(element: kotlinx.serialization.json.JsonElement?): GitHead? {
            val head = runCatching { element?.jsonObject }.getOrNull() ?: return null
            val name = head["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            return when (head["kind"]?.jsonPrimitive?.contentOrNull) {
                "branch" -> GitHead.Branch(name)
                "detached" ->
                    GitHead.Detached(head["commit"]?.jsonPrimitive?.contentOrNull.orEmpty())
                "unborn" -> GitHead.Unborn(name)
                // A fourth state this build has not been taught reads as no
                // head, which the heading says plainly. Guessing "branch"
                // would put a name in front of the model that nothing gave it.
                else -> null
            }
        }

        private fun changesIn(element: kotlinx.serialization.json.JsonElement?) =
            runCatching { element?.jsonArray }.getOrNull().orEmpty().mapNotNull {
                val change = it.jsonObject
                val path = change["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                GitChange(path, change["kind"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }

        private fun pathsIn(element: kotlinx.serialization.json.JsonElement?) =
            runCatching { element?.jsonArray }.getOrNull().orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull }
    }
}

/** Read the repository. */
class GitStatusTool(private val repository: Repository) : Tool {
    override val name = "read_repository"

    override val purpose =
        "See the state of the repository: which branch, what is staged, what is " +
            "changed and not staged, and what is untracked. Read it before " +
            "staging or committing anything, and after, to see what happened."

    override val schema = """{"type":"object","properties":{}}"""

    /** # Rely
     *  Nothing. There is no capability to obtain: the repository is the app's
     *  own directory, and reading it is disk work rather than a dialog. */
    override suspend fun run(arguments: String): String =
        answer(GitStatus.from(repository.status()))

    companion object {
        /** An envelope as the model reads it, whichever half arrived. */
        fun answer(read: Result<GitStatus>?): String = when {
            read == null -> "the repository could not be read at all"
            read.isFailure -> read.exceptionOrNull()?.message.orEmpty()
            else -> describe(read.getOrThrow())
        }

        /** One status, as lines. Separate from [run] so the rendering, which is
         *  where the decisions are, is exercised without a repository. */
        fun describe(status: GitStatus): String {
            val lines = mutableListOf(heading(status.head))
            lines += section("Staged", status.staged)
            lines += section("Not staged", status.unstaged)
            lines += listing("Untracked:", status.untracked)
            // Its own section, named for what it is. Rendered among the
            // changes, a conflicted path is one the model stages and commits.
            lines += listing("Conflicted, and not committable until resolved:", status.conflicted)

            // A sentence rather than a heading with nothing under it, which
            // reads as a rendering that gave up half way.
            if (lines.size == 1) lines += "Nothing staged, nothing changed, nothing untracked."
            return lines.joinToString("\n")
        }

        private fun heading(head: GitHead?): String = when (head) {
            is GitHead.Branch -> "On branch ${head.name}."
            is GitHead.Detached ->
                "Not on a branch: at commit ${head.commit}. " +
                    "A commit here belongs to no branch."
            is GitHead.Unborn ->
                "On branch ${head.name}, which has no commits yet. " +
                    "The next commit creates it."
            null -> "The repository's head was not read."
        }

        private fun section(title: String, changes: List<GitChange>): List<String> {
            if (changes.isEmpty()) return emptyList()
            // Padded so the paths line up. The model reads down the column of
            // paths, and a ragged left edge makes it read the kinds instead.
            val width = changes.maxOf { it.kind.length }
            return listOf("", "$title:") +
                changes.map { "  ${it.kind.padEnd(width)}  ${it.path}" }
        }

        private fun listing(title: String, paths: List<String>): List<String> =
            if (paths.isEmpty()) emptyList() else listOf("", title) + paths.map { "  $it" }
    }
}
