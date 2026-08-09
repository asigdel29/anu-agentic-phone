// Repository.kt — a git repository, as Kotlin sees it.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Deliberately not Memory.kt's shape. A repository is a path rather than a
// handle: git::open runs inside every call on the native side, so there is
// nothing to hold open and nothing to close. Copying that ceremony would invent
// a lifetime which does not exist.
//
// The symbol names are the contract and nothing checks them at build time, so
// the parity test in router/src/jni.rs reads this file too — which makes its
// path part of the contract, the way Core.kt's is.
//
// Every call answers the envelope the C ABI already defines. Decoding it is the
// tools' job, above this: what a model should be told about a failed commit is
// a decision rather than a deserialisation.

package com.getlora.wattrouter

/**
 * Paths as a JSON array of strings.
 *
 * Outside the class on purpose: touching [Repository]'s companion runs
 * `System.loadLibrary`, so a JVM test of this would fail on the host for a
 * reason that has nothing to do with encoding. Hand-written for
 * Conversation.kt's reason — the plugin would be a version to keep in step
 * with AGP for four lines.
 */
internal fun encodePaths(paths: List<String>): String = paths.joinToString(
    separator = ",",
    prefix = "[",
    postfix = "]",
) { path ->
    val escaped = path
        // The backslash first, or the escapes added below get escaped again.
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        // A control character in a filename is legal on disk and invalid in
        // JSON. Left raw, a path with a newline produces an envelope the core
        // cannot parse and so cannot explain.
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    "\"$escaped\""
}

/**
 * A repository, as a seam.
 *
 * [Repository] loads the shared library when its class initialises, so a tool
 * holding one cannot be built on the host at all — and the tools' own decisions,
 * which are what to refuse and what to say, need no repository to exercise. The
 * same split `Calendars`, `Directory` and `Whereabouts` are on.
 *
 * Every call answers the envelope the C ABI defines, undecoded: what a model
 * should be told about a failed commit is a decision, made above this.
 */
interface Worktree {
    fun head(): String?

    fun status(): String?

    fun add(paths: List<String>): String?

    fun commit(message: String): String?
}

/**
 * One repository on disk.
 *
 * Cheap to build and cheap to throw away: it is a path and nothing else, so a
 * second instance over the same directory is not a second anything.
 *
 * @property path the working tree's root, absolute. A relative one resolves
 *   against the process's working directory, which on Android is `/` — so the
 *   mistake it looks like is not the one it is.
 */
class Repository(val path: String) : Worktree {

    /** Where `HEAD` points: a branch, a commit, or a branch with no commits
     *  yet. Answers the envelope, or null if the runtime could not allocate. */
    override fun head(): String? = nativeHead(path)

    /** The working tree, against the index and the head. */
    override fun status(): String? = nativeStatus(path)

    /**
     * Stage paths, and answer with the status that results.
     *
     * @param paths relative to the repository root, WHERE a directory stages
     *   what is under it. Encoded as JSON here because that is the shape the C
     *   ABI takes and the shape the model wrote — turning it into an array on
     *   the way through would be a third shape for one value.
     */
    override fun add(paths: List<String>): String? = nativeAdd(path, encodePaths(paths))

    /**
     * Commit what is staged. Committing nothing is an error rather than a
     * no-op: libgit2 writes a commit whose tree matches its parent without
     * complaint, and a model doing that in a loop believes it is progressing.
     */
    override fun commit(message: String): String? = nativeCommit(path, message)

    private companion object {
        init {
            // The same library Core loads. Loading it twice in a process is a
            // no-op, and not loading it here would make reaching a repository
            // depend on somebody having touched Core first.
            System.loadLibrary("wattrouter")
        }

        @JvmStatic private external fun nativeHead(path: String?): String?
        @JvmStatic private external fun nativeStatus(path: String?): String?
        @JvmStatic private external fun nativeAdd(path: String?, pathsJson: String?): String?
        @JvmStatic private external fun nativeCommit(path: String?, message: String?): String?
    }
}
