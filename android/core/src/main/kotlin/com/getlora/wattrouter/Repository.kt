// Repository.kt: a git repository, as Kotlin sees it.
//
// History
//   2026-08-09  A. Sigdel  Created.
//   2026-08-12  A. Sigdel  Reaches a remote, so the work has somewhere to go.
//   2026-08-12  A. Sigdel  Sends and takes, with the two refusals that matter.
//
// Deliberately not Memory.kt's shape. A repository is a path rather than a
// handle: git::open runs inside every call on the native side, so there is
// nothing to hold open and nothing to close. Copying that ceremony would invent
// a lifetime which does not exist.
//
// The symbol names are the contract and nothing checks them at build time, so
// the parity test in router/src/jni.rs reads this file too, which makes its
// path part of the contract, the way Core.kt's is.
//
// Every call answers the envelope answer.rs defines, which is one shape for
// everything crossing this boundary. Decoding it is the tools' job, above this:
// what a model should be told about a failed commit is a decision rather than a
// deserialisation. It used to say the C ABI defines it; #565 removed that and
// #638 caught four other files still saying so.

package com.getlora.wattrouter

/**
 * Paths as a JSON array of strings.
 *
 * Outside the class on purpose: touching [Repository]'s companion runs
 * `System.loadLibrary`, so a JVM test of this would fail on the host for a
 * reason that has nothing to do with encoding. Hand-written for
 * Conversation.kt's reason: the plugin would be a version to keep in step
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
 * holding one cannot be built on the host at all, and the tools' own decisions,
 * which are what to refuse and what to say, need no repository to exercise. The
 * same split `Calendars`, `Directory` and `Whereabouts` are on.
 *
 * Every call answers the core's envelope undecoded: what a model should be told
 * about a failed commit is a decision, made above this.
 */
interface Worktree {
    /**
     * Make the directory into a repository, or say it already was one.
     *
     * The two successes are separate answers rather than one: `git init` is
     * idempotent, and a model that cannot tell "made you one" from "there
     * already was one" reports having started work it is midway through.
     */
    fun init(): String?

    /**
     * Say who commits from here.
     *
     * Not on this interface because a tool needs it. No tool does, and none
     * should: whose name goes on the work is a claim about a person, and a
     * model choosing one is a model deciding whose. The app calls this when it
     * sets the repository up, from what somebody typed once.
     *
     * Until it has been called, every [commit] on a phone fails. A phone has no
     * `~/.gitconfig` and no shell to write one with, and the core reads the
     * name and the email from the repository's own configuration.
     *
     * @param name and [email] as typed. Blank in either is refused rather than
     *   written, because a blank reads back as configured and moves the failure
     *   to the signature, where the message says nothing about which half is
     *   missing.
     */
    fun identify(name: String, email: String): String?

    fun head(): String?

    fun status(): String?

    fun add(paths: List<String>): String?

    fun commit(message: String): String?

    /**
     * Point a remote somewhere, and say what that changed.
     *
     * Three answers rather than one success: added, moved, or unchanged. A
     * caller told only that it worked cannot tell whether it has just changed
     * where somebody's work goes, and the moved case carries the URL it used to
     * point at, because that is the only record of it left anywhere afterwards.
     *
     * An `https://` URL is refused with the ssh form named. This build links
     * only the ssh transport; see `docs/decisions/pushing-from-a-phone.md`.
     */
    fun remoteSet(name: String, url: String): String?

    /**
     * Bring back what a remote has, merging nothing.
     *
     * Answers the reference names that moved, and an empty list when the remote
     * had nothing this repository did not already have. That is a state rather
     * than a failure and reads as one.
     *
     * Nothing in the working tree changes, which is what makes this the one
     * network call that cannot lose anything.
     */
    fun fetch(name: String): String?

    /**
     * Send a branch to a remote, and refuse rather than overwrite.
     *
     * There is no force and no argument that could become one. A remote that
     * refuses the reference has work this repository does not, which is not a
     * transient failure and not something to retry: the answer says what
     * happened and whoever is holding the phone decides whose work survives.
     * `docs/decisions/pushing-from-a-phone.md` argues that at length.
     */
    fun push(remote: String, branch: String): String?

    /**
     * Take what a remote has, if that can be done without merging.
     *
     * Fast-forward only. A merge needs conflict resolution, that needs a diff
     * surface, and there is none anywhere in this product; a conflicted index
     * on a phone with no way to look at it is worse than being told no.
     *
     * Three answers: already here, fast-forwarded, or started. The last is the
     * ordinary case rather than an edge one, because a repository made by
     * `init_repository` and then pointed at a remote has no branch at all.
     */
    fun pull(remote: String, branch: String): String?
}

/**
 * What a call over the network offers, and what it checks who answered against.
 *
 * @property key an OpenSSH private key, as text. In memory rather than on disk,
 *   which is `docs/decisions/pushing-from-a-phone.md`: the only copy at rest is
 *   the sealed one.
 * @property pins the file host keys are remembered in. Absolute.
 */
data class Reach(val key: String, val pins: String)

/**
 * One repository on disk.
 *
 * Cheap to build and cheap to throw away: it is a path and a way of asking for
 * a key, so a second instance over the same directory is not a second anything.
 *
 * @property path the working tree's root, absolute. A relative one resolves
 *   against the process's working directory, which on Android is `/`, so the
 *   mistake it looks like is not the one it is.
 * @property reach how to reach a host, asked once per call rather than held.
 *   [Signed] takes its identity the same way and for the same reason: a key
 *   captured at construction would be whichever existed at launch, and this one
 *   can stop existing between two calls, since the keystore drops its entries
 *   when the screen lock is removed. Answering null is a repository that can
 *   still reach a path remote and nothing else, which is what every caller
 *   before #467 was.
 */
class Repository(
    val path: String,
    private val reach: () -> Reach? = { null },
) : Worktree {

    /**
     * Make [path] into a repository, or say it already was one.
     *
     * The directory is created if it is not there. On a fresh install
     * `filesDir/work` is an empty directory and there is no shell to make one
     * with, which is what #393 was about.
     */
    override fun init(): String? = nativeInit(path)

    /** Say who commits from here, writing it where git keeps it. */
    override fun identify(name: String, email: String): String? =
        nativeIdentify(path, name, email)

    /** Where `HEAD` points: a branch, a commit, or a branch with no commits
     *  yet. Answers the envelope, or null if the runtime could not allocate. */
    override fun head(): String? = nativeHead(path)

    /** The working tree, against the index and the head. */
    override fun status(): String? = nativeStatus(path)

    /**
     * Stage paths, and answer with the status that results.
     *
     * @param paths relative to the repository root, WHERE a directory stages
     *   what is under it. Encoded as JSON here because that is the shape the
     *   core takes and the shape the model wrote. Turning it into an array on
     *   the way through would be a third shape for one value.
     */
    override fun add(paths: List<String>): String? = nativeAdd(path, encodePaths(paths))

    /**
     * Commit what is staged. Committing nothing is an error rather than a
     * no-op: libgit2 writes a commit whose tree matches its parent without
     * complaint, and a model doing that in a loop believes it is progressing.
     */
    override fun commit(message: String): String? = nativeCommit(path, message)

    /** Point a remote somewhere, and say whether that added or moved one. */
    override fun remoteSet(name: String, url: String): String? = nativeRemoteSet(path, name, url)

    /** Bring back what a remote has, and answer with what moved. */
    override fun fetch(name: String): String? = reach().let { nativeFetch(path, name, it?.key, it?.pins) }

    /** Send a branch, and answer with the refusal if there is one. */
    override fun push(remote: String, branch: String): String? =
        reach().let { nativePush(path, remote, branch, it?.key, it?.pins) }

    /** Fast-forward to what the remote has, or say why that cannot be done. */
    override fun pull(remote: String, branch: String): String? =
        reach().let { nativePull(path, remote, branch, it?.key, it?.pins) }

    private companion object {
        init {
            // The same library Core loads. Loading it twice in a process is a
            // no-op, and not loading it here would make reaching a repository
            // depend on somebody having touched Core first.
            System.loadLibrary("wattrouter")
        }

        @JvmStatic private external fun nativeInit(path: String?): String?

        @JvmStatic private external fun nativeIdentify(
            path: String?,
            name: String?,
            email: String?,
        ): String?

        @JvmStatic private external fun nativeHead(path: String?): String?
        @JvmStatic private external fun nativeStatus(path: String?): String?
        @JvmStatic private external fun nativeAdd(path: String?, pathsJson: String?): String?
        @JvmStatic private external fun nativeCommit(path: String?, message: String?): String?

        @JvmStatic private external fun nativeRemoteSet(
            path: String?,
            name: String?,
            url: String?,
        ): String?

        @JvmStatic private external fun nativeFetch(
            path: String?,
            name: String?,
            key: String?,
            pins: String?,
        ): String?

        @JvmStatic private external fun nativePush(
            path: String?,
            remote: String?,
            branch: String?,
            key: String?,
            pins: String?,
        ): String?

        @JvmStatic private external fun nativePull(
            path: String?,
            remote: String?,
            branch: String?,
            key: String?,
            pins: String?,
        ): String?
    }
}
