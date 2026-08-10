// Credential.kt: the one secret the stack needs, kept between launches.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// The Swift twin is Credential.swift, and the decision worth carrying over is
// the trim. A key pasted from a terminal or a password manager arrives with a
// trailing newline; the provider answers 401 and says nothing about why, and
// the person retypes a key that was correct both times.
//
// `clean` is separate, and public to this module, so that decision is reachable
// from a JVM test. Everything else here needs AndroidKeyStore and so needs a
// device, and the part with a bug in it should not be the part that only runs
// on an emulator.

package com.getlora.wattrouter

import android.content.Context
import android.content.SharedPreferences

/** The NeuralWatt key, sealed on disk. */
class Credential(private val store: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE),
    )

    /** Whether a key has been stored, without decrypting it to find out. */
    val isStored: Boolean
        get() = store.contains(KEY)

    /**
     * Keep [typed] for later launches.
     *
     * @return false if there was nothing usable in it, or if the keystore
     *   refused to seal it. Either way nothing was stored.
     */
    fun store(typed: String): Boolean {
        val cleaned = clean(typed) ?: return false
        val sealed = Keystore.seal(ALIAS, cleaned) ?: return false
        return store.edit().putString(KEY, sealed).commit()
    }

    /**
     * The key, or null if none was stored or it can no longer be decrypted.
     *
     * The second case is real: the keystore drops its entries when the screen
     * lock is removed on some devices, and the honest answer then is the same
     * as never having had one: sign in again.
     */
    fun read(): String? = store.getString(KEY, null)?.let { Keystore.open(ALIAS, it) }

    /** Forget it, both the ciphertext and the key that would open it. */
    fun forget(): Boolean {
        val cleared = store.edit().remove(KEY).commit()
        // Order matters only in that both must happen. Dropping the key alone
        // would leave bytes that decrypt to nothing and read as a stored
        // credential to `isStored`.
        return Keystore.discard(ALIAS) && cleared
    }

    companion object {
        private const val FILE = "wattrouter-credential"
        private const val KEY = "neuralwatt-api-key"
        private const val ALIAS = "wattrouter-credential"

        /**
         * What is worth storing from what somebody typed, or null if nothing is.
         *
         * Trims because a pasted key carries a trailing newline and the provider
         * answers 401 without saying so.
         */
        fun clean(typed: String): String? = typed.trim().ifEmpty { null }
    }
}
