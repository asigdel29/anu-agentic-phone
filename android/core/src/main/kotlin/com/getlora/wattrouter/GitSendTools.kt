// GitSendTools.kt: sending a branch, and taking one, with what neither will do.
//
// History
//   2026-08-13  A. Sigdel  Created with #467.
//
// Contents
//   sent    What a push turned out to be, in words.
//   taken   Which of pull's three answers arrived, in words.
//   PushTool  Send a branch to a remote.
//   PullTool  Take what a remote has, if that needs no merge.
//
// Apart from GitRemoteTools.kt because these two are the pair that can lose
// somebody's work, and everything here is about what they refuse to do rather
// than about what they do.
//
// force appears in neither schema. Not defaulted to false, not hidden, not
// present, and this comment is the closest it comes to appearing at all.
// docs/decisions/pushing-from-a-phone.md: an argument a model can set is one it
// will set, eventually, on the turn where setting it makes the error go away,
// and the cost of setting it wrongly here is the only one on that list nobody
// can undo. The same holds for pull from the other direction: a merge needs
// conflict resolution, that needs a diff surface, and there is none in this
// product.
//
// So both purposes say what happens when the answer is no, in the words a model
// is meant to relay rather than route around. A refusal a model reads as a
// transient failure is a refusal it retries.

package com.getlora.wattrouter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Shared by the two readers below, which take an envelope the core wrote. */
private val sending = Json { ignoreUnknownKeys = true }

/**
 * What a push turned out to be, in words.
 *
 * The one envelope here with nothing in its `ok`: a push that worked has no
 * payload, because what it did is that the remote now has what this repository
 * has. Everything interesting about this call is in the refusals.
 */
internal fun sent(envelope: String?, branch: String): String {
    if (envelope == null) return "the push could not be attempted at all"

    val body = runCatching { sending.parseToJsonElement(envelope).jsonObject }.getOrNull()
        ?: return "the repository answered nothing readable"
    body["error"]?.jsonPrimitive?.contentOrNull?.let { return it }

    return "sent $branch. The remote now has what this repository has"
}

/**
 * Which of `pull`'s three answers arrived, in words.
 *
 * [pointed]'s shape. Started is the ordinary case rather than an edge one: a
 * repository made by `init_repository` and then pointed at a remote has no
 * branch at all, so the first pull anybody does is that one.
 */
internal fun taken(envelope: String?): String {
    if (envelope == null) return "the pull could not be attempted at all"

    val body = runCatching { sending.parseToJsonElement(envelope).jsonObject }.getOrNull()
        ?: return "the repository answered nothing readable"
    body["error"]?.jsonPrimitive?.contentOrNull?.let { return it }

    val ok = runCatching { body["ok"]?.jsonObject }.getOrNull()
    val commit = ok?.get("commit")?.jsonPrimitive?.contentOrNull
    return when (ok?.get("kind")?.jsonPrimitive?.contentOrNull) {
        "already_here" -> "the remote had nothing this branch did not already have"
        "fast_forwarded" -> "moved this branch forward to $commit"
        "started" -> "there was no such branch here, so it was created at $commit"
        else -> "the repository answered nothing readable"
    }
}

/** Send a branch to a remote, and refuse rather than overwrite. */
class PushTool(private val repository: Worktree) : Tool {
    override val name = "push"

    override val purpose =
        "Send a branch to a remote. There is no way to force this and no " +
            "argument that could become one. If the remote refuses, it has work " +
            "this repository does not, and that is not a transient failure and " +
            "not something to retry or work around: say so, say what is on the " +
            "branch, and let the person decide whose work survives. Fetching " +
            "first and looking at what is there is the useful next step."

    override val schema = """
        {"type":"object","properties":{"remote":{"type":"string",
        "description":"Which remote to send to. origin is the usual one."},"branch":{"type":"string",
        "description":"Which local branch to send. read_repository says which one is checked out."}},
        "required":["remote","branch"]}
    """.trimIndent().replace("\n", "")

    /** # Rely
     *  Reaches a network, as [FetchTool], and is the one tool here that changes
     *  something somebody else can see. */
    override suspend fun run(arguments: String): String {
        val remote = Tools.field(arguments, "remote").trim()
        val branch = Tools.field(arguments, "branch").trim()
        if (remote.isEmpty()) return "a push needs a remote to send to, so nothing was sent"
        if (branch.isEmpty()) return "a push needs a branch to send, so nothing was sent"

        return sent(withContext(Dispatchers.IO) { repository.push(remote, branch) }, branch)
    }
}

/** Take what a remote has, if that can be done without merging. */
class PullTool(private val repository: Worktree) : Tool {
    override val name = "pull"

    override val purpose =
        "Take what a remote has, when that only moves this branch forward. If " +
            "both this branch and the remote have moved on, taking it would need " +
            "a merge, and there is nothing on this phone to resolve a conflict " +
            "with, so it is refused and nothing changes. Uncommitted work in the " +
            "way also stops it: commit or discard first. Answers whether " +
            "anything arrived."

    override val schema = """
        {"type":"object","properties":{"remote":{"type":"string",
        "description":"Which remote to take from. origin is the usual one."},"branch":{"type":"string",
        "description":"Which branch to take. It need not exist here yet."}},
        "required":["remote","branch"]}
    """.trimIndent().replace("\n", "")

    /** # Rely
     *  Reaches a network, as [FetchTool], and unlike it may move the working
     *  tree: a fast-forward checks out what arrived. */
    override suspend fun run(arguments: String): String {
        val remote = Tools.field(arguments, "remote").trim()
        val branch = Tools.field(arguments, "branch").trim()
        if (remote.isEmpty()) return "a pull needs a remote to take from, so nothing was taken"
        if (branch.isEmpty()) return "a pull needs a branch to take, so nothing was taken"

        return taken(withContext(Dispatchers.IO) { repository.pull(remote, branch) })
    }
}
