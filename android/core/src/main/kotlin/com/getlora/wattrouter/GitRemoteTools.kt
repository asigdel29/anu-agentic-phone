// GitRemoteTools.kt: pointing a repository somewhere, and taking what is there.
//
// History
//   2026-08-13  A. Sigdel  Created with #467.
//
// Contents
//   pointed    Which of remote_set's three answers arrived, in words.
//   brought    What a fetch moved, in words.
//   SetRemoteTool  Point a remote at a URL.
//   FetchTool      Bring back what a remote has.
//
// A file of its own rather than GitWriteTools.kt's fourth and fifth, because
// these two are the half that reaches a network: they can fail for reasons no
// local call has, they are the first tools that need a key, and the two that
// can lose somebody's work are a third file after them.
//
// Neither of these can lose anything, which is why they are first. remote_set
// writes one config value and fetch writes only under refs/remotes: nothing in
// the working tree moves, and nothing anybody committed is touched. push and
// pull are the pair that can, and they are argued separately.

package com.getlora.wattrouter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Shared by the two readers below, which take an envelope the core wrote. */
private val remotes = Json { ignoreUnknownKeys = true }

/**
 * Which of `remote_set`'s three answers arrived, in words.
 *
 * [made]'s shape, and the same argument: added, moved and unchanged are three
 * things a model acts on differently, and a caller told only "done" cannot tell
 * whether it has just repointed a remote somebody else set. The moved case
 * carries where it pointed, because after this that is the only record of it
 * anywhere.
 */
internal fun pointed(envelope: String?): String {
    if (envelope == null) return "the remote could not be set at all"

    val body = runCatching { remotes.parseToJsonElement(envelope).jsonObject }.getOrNull()
        ?: return "the repository answered nothing readable"
    body["error"]?.jsonPrimitive?.contentOrNull?.let { return it }

    val ok = runCatching { body["ok"]?.jsonObject }.getOrNull()
    return when (ok?.get("kind")?.jsonPrimitive?.contentOrNull) {
        "added" -> "added the remote, which was not there before"
        "moved" ->
            "the remote was already there and now points somewhere else. It " +
                "used to point at ${ok["from"]?.jsonPrimitive?.contentOrNull}, " +
                "which is not recorded anywhere else now"
        "unchanged" -> "the remote already pointed there, so nothing was written"
        else -> "the repository answered nothing readable"
    }
}

/**
 * What a fetch moved, in words.
 *
 * An empty list is a state rather than a failure and reads as one: the remote
 * had nothing this repository did not already have. Saying "fetched" for both
 * leaves a model unable to tell a busy remote from a quiet one, and it will
 * fetch again to find out.
 */
internal fun brought(envelope: String?): String {
    if (envelope == null) return "the fetch could not be attempted at all"

    val body = runCatching { remotes.parseToJsonElement(envelope).jsonObject }.getOrNull()
        ?: return "the repository answered nothing readable"
    body["error"]?.jsonPrimitive?.contentOrNull?.let { return it }

    val moved = runCatching { body["ok"]?.jsonArray?.map { it.jsonPrimitive.content } }
        .getOrNull() ?: return "the repository answered nothing readable"

    if (moved.isEmpty()) {
        return "the remote had nothing this repository did not already have"
    }
    return "these moved:\n" + moved.joinToString("\n")
}

/** Point a remote at a URL. */
class SetRemoteTool(private val repository: Worktree) : Tool {
    override val name = "set_remote"

    override val purpose =
        "Say where a remote called something lives, so this repository has " +
            "somewhere to send to and take from. Use the ssh form, which is " +
            "git@host:owner/repository.git on most forges: an https URL is " +
            "refused, because this phone signs in with a key rather than a " +
            "password. Answers whether that added a remote, moved one that was " +
            "already there, or changed nothing."

    override val schema = """
        {"type":"object","properties":{"name":{"type":"string",
        "description":"What to call it. origin is the usual one."},"url":{"type":"string",
        "description":"Where it lives, in the ssh form git@host:owner/repository.git."}},
        "required":["name","url"]}
    """.trimIndent().replace("\n", "")

    /** # Rely
     *  Nothing, as [GitAddTool]. This writes one value in the repository's own
     *  configuration and reaches no network at all. */
    override suspend fun run(arguments: String): String {
        val name = Tools.field(arguments, "name").trim()
        val url = Tools.field(arguments, "url").trim()
        if (name.isEmpty()) return "a remote needs a name, such as origin, so nothing was set"
        if (url.isEmpty()) return "a remote needs a url, so nothing was set"

        return pointed(withContext(Dispatchers.IO) { repository.remoteSet(name, url) })
    }
}

/** Bring back what a remote has, merging nothing. */
class FetchTool(private val repository: Worktree) : Tool {
    override val name = "fetch"

    override val purpose =
        "Bring back what a remote has, without changing anything in the working " +
            "directory. Safe when unsure: nothing that has been committed here " +
            "is touched and nothing is merged, so this only ever adds what the " +
            "remote knows to what this repository knows. Answers with the " +
            "references that moved, or says the remote had nothing new."

    override val schema = """
        {"type":"object","properties":{"remote":{"type":"string",
        "description":"Which remote to fetch from. origin is the usual one."}},
        "required":["remote"]}
    """.trimIndent().replace("\n", "")

    /** # Rely
     *  Reaches a network, so this suspends for as long as a host takes to
     *  answer and can fail for reasons no other git tool has: an unreachable
     *  host, a key the remote does not know, a host whose key has changed. */
    override suspend fun run(arguments: String): String {
        val remote = Tools.field(arguments, "remote").trim()
        if (remote.isEmpty()) return "a fetch needs a remote to fetch from, so nothing was fetched"

        return brought(withContext(Dispatchers.IO) { repository.fetch(remote) })
    }
}
