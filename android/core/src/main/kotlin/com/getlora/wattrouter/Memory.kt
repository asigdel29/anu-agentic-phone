// Memory.kt: the store, as Kotlin sees it.
//
// History
//   2026-08-09  A. Sigdel  Created.
//   2026-08-09  A. Sigdel  Serialised the three calls against each other, now
//                          that they can run on different threads.
//
// Core.kt's shape, for the same reasons: a private constructor over a handle
// the native side owns, AutoCloseable, and an idempotent close, because a
// handle cleared twice is a thing that happens and a double free is not a thing
// to allow.
//
// The symbol names are the contract and nothing checks them at build time, so
// the parity test in router/src/jni.rs reads this file too.
//
// The horizon runs inside open. #284 bounds what a store loads, and doing it on
// the way in means a launch cannot be the thing that takes a minute.

package com.getlora.wattrouter

/**
 * Everything remembered, across conversations.
 *
 * One instance per process, like [Core]: the store is behind a mutex on the
 * native side, so a second one is a second mutex over the same file.
 */
class Memory private constructor(private var handle: Long) : AutoCloseable {

    /**
     * Put a turn in.
     *
     * @param at seconds since the epoch. A turn's age is what tells "the bins
     *   go out Tuesday" said last year from the same sentence said today.
     * @return the envelope, or null if the runtime could not allocate. A turn
     *   with no text is refused inside it rather than stored: nothing indexes
     *   it, so it could never be recalled and would still count against the
     *   horizon.
     *
     * # Atomic
     * Free of interference with [close]. See [Core.decide]: until #474 every
     * one of these ran on the main thread, and moving them off it makes an
     * Activity destroyed mid-turn a free under a call in flight.
     */
    @Synchronized
    fun remember(text: String, speaker: String, session: String, at: Long): String? {
        check(handle != 0L) { "this store has been closed" }
        return nativeRemember(handle, session, speaker, text, at)
    }

    /**
     * Ask it something.
     *
     * @param most how much evidence to return, or 0 for the store's own default
     *   rather than nothing.
     * @return the envelope, carrying the route taken and what was found.
     *
     * # Atomic
     * As [remember].
     */
    @Synchronized
    fun recall(query: String, most: Int = 0): String? {
        check(handle != 0L) { "this store has been closed" }
        return nativeRecall(handle, query, most.toLong())
    }

    /**
     * Release the store.
     *
     * # Atomic
     * Waits for a [remember] or [recall] in flight rather than freeing under
     * it. Idempotent, so a caller that closed it already need not check.
     */
    @Synchronized
    override fun close() {
        if (handle == 0L) return
        nativeFree(handle)
        handle = 0L
    }

    companion object {
        init {
            System.loadLibrary("wattrouter")
        }

        /**
         * Open the store at [path], bounding it to [keep] turns on the way in.
         *
         * @return a store, or null if it would not open. The native side
         *   reports a missing directory, a corrupt file and a failed horizon
         *   the same way, and inventing a distinction here would be inventing a
         *   reason.
         */
        fun open(path: String, keep: Int = DEFAULT_KEEP): Memory? {
            val handle = nativeOpen(path, keep.toLong())
            return if (handle == 0L) null else Memory(handle)
        }

        /**
         * Turns kept when nobody says otherwise.
         *
         * A phone is not a board: the whole store loads at open, so this is the
         * number that decides how long a launch takes.
         */
        const val DEFAULT_KEEP = 5000

        @JvmStatic private external fun nativeOpen(path: String?, keep: Long): Long
        @JvmStatic private external fun nativeFree(handle: Long)
        @JvmStatic private external fun nativeRemember(
            handle: Long,
            session: String?,
            speaker: String?,
            text: String?,
            ts: Long,
        ): String?
        @JvmStatic private external fun nativeRecall(
            handle: Long,
            query: String?,
            most: Long,
        ): String?
    }
}
