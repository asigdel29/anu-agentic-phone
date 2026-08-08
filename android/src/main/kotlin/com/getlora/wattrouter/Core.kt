// Core.kt — the routing core, as Kotlin sees it.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// The declarations libwattrouter.so satisfies, and nothing else. There is no
// Gradle module around this yet: `just android-core` produces the library and
// this says what is in it, so the two can be wrong together rather than one of
// them being wrong alone.
//
// The symbol names are the contract, and nothing checks them at build time. A
// mismatch is an UnsatisfiedLinkError the first time somebody runs the app, so
// `the_symbols_match_the_kotlin` in router/src/jni.rs reads this file and holds
// the two in step — the way the header parity test does for C.
//
// Deciding joins this next. What it will answer with is the envelope Swift
// already decodes, so Android reads one shape rather than a second.

package com.getlora.wattrouter

/**
 * The routing core.
 *
 * One instance per process. The handle is a pointer the native side owns, so
 * `close` must run exactly once and nothing may call `decide` afterwards —
 * which is what [AutoCloseable] is for and why this is not an object.
 */
class Core private constructor(private var handle: Long) : AutoCloseable {

    companion object {
        init {
            System.loadLibrary("wattrouter")
        }

        /**
         * Build a router, configured from the environment as the server is.
         *
         * @return a core, or null if configuration was rejected. Null covers a
         *   missing credential and an unparseable setting alike — the native
         *   side cannot say which, and pretending otherwise would invent a
         *   reason.
         */
        fun open(): Core? {
            val handle = nativeNew()
            return if (handle == 0L) null else Core(handle)
        }

        @JvmStatic private external fun nativeNew(): Long
        @JvmStatic private external fun nativeFree(handle: Long)
    }

    /**
     * Release the native router.
     *
     * Idempotent, because a handle cleared twice is a thing that happens and a
     * double free is not a thing to allow.
     */
    override fun close() {
        if (handle == 0L) return
        nativeFree(handle)
        handle = 0L
    }
}
