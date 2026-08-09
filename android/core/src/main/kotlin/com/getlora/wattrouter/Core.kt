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
// One call rather than the four the C ABI offers. Reading a tier and then
// walking its chain is four crossings from Kotlin where it is four function
// calls from Swift, and a decision that arrives in pieces is one a caller can
// assemble wrongly. What comes back is the envelope Swift already decodes.

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
         * Build a router.
         *
         * @param credential the provider key. The core reads it from the
         *   environment, which Kotlin cannot write, so it is handed across and
         *   installed there — the same thing Startup.install does on iOS.
         * @return a core, or null if the credential was refused or the
         *   configuration was. Null covers both, because the native side reports
         *   every configuration fault the same way and inventing a distinction
         *   here would be inventing a reason.
         */
        fun open(credential: String): Core? {
            if (!nativeConfigure(credential)) return null
            val handle = nativeNew()
            return if (handle == 0L) null else Core(handle)
        }

        @JvmStatic private external fun nativeConfigure(credential: String?): Boolean
        @JvmStatic private external fun nativeNew(): Long
        @JvmStatic private external fun nativeFree(handle: Long)
        @JvmStatic private external fun nativeDecide(
            handle: Long,
            body: String?,
            session: String?,
        ): String?
    }

    /**
     * Decide which tier serves a request, and what stands behind it.
     *
     * @param body an OpenAI-shaped chat completion request.
     * @param session identifies the conversation, so a tier it has already been
     *   raised to is not dropped partway through. Empty means no stickiness.
     * @return a JSON envelope — `{"ok": …}` with the tier, the reason, the score
     *   and the chain, or `{"error": "…"}`. Null only if the runtime could not
     *   allocate a string, which is an out-of-memory condition rather than an
     *   answer.
     */
    fun decide(body: String, session: String = ""): String? {
        check(handle != 0L) { "this core has been closed" }
        return nativeDecide(handle, body, session)
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
