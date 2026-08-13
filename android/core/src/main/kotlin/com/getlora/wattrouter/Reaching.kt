// Reaching.kt: the key the phone pushes with, kept between launches.
//
// History
//   2026-08-13  A. Sigdel  Created with #467.
//
// Credential.kt's shape, under a second alias, which is what
// docs/decisions/pushing-from-a-phone.md said this would cost: "Keystore is
// already parameterised by alias, so this costs a constant rather than a
// mechanism." One difference from that file and it is worth stating: this
// secret is one a forge can revoke without the phone's help, where the provider
// key is not.
//
// Made once and then read. There is no store(), because nothing is ever pasted
// in: the only way a key gets here is [Ssh.make], in this process.

package com.getlora.wattrouter

import android.content.Context
import android.content.SharedPreferences

/** The key the phone pushes with, sealed on disk. */
class Reaching(private val store: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE),
    )

    /** Whether a key has been made, without decrypting it to find out. */
    val isMade: Boolean
        get() = store.contains(SECRET)

    /**
     * The public half, to be shown and pasted into a forge.
     *
     * Stored beside the sealed private half rather than derived from it, so
     * showing it needs no keystore and no decryption: this is the one part of a
     * key that is not a secret, and reading it should not have the failure modes
     * of reading one.
     */
    val shown: String?
        get() = store.getString(SHOWN, null)

    /**
     * Make one if there is not one, and answer the public half either way.
     *
     * @return the line to paste into a forge, or null if the platform refused
     *   to generate a key or the keystore refused to seal it. Nothing partial
     *   is written: a public half stored without its private half would show
     *   somebody a key to authorise that the phone could never use.
     */
    fun ensure(): String? {
        shown?.let { return it }

        val made = Ssh.make() ?: return null
        val sealed = Keystore.seal(ALIAS, made.secret) ?: return null
        return if (store.edit().putString(SECRET, sealed).putString(SHOWN, made.shown).commit()) {
            made.shown
        } else {
            null
        }
    }

    /**
     * The private half, or null if none was made or it can no longer be opened.
     *
     * The second case is [Credential.read]'s: the keystore drops its entries
     * when the screen lock is removed on some devices. The honest answer then is
     * that this phone has no key, and [forget] is how somebody gets a new one,
     * because a forge holds the public half of a key nothing here can use.
     */
    fun secret(): String? = store.getString(SECRET, null)?.let { Keystore.open(ALIAS, it) }

    /** Forget it, both halves and the key that would open the sealed one. */
    fun forget(): Boolean {
        val cleared = store.edit().remove(SECRET).remove(SHOWN).commit()
        return Keystore.discard(ALIAS) && cleared
    }

    private companion object {
        const val FILE = "wattrouter-reaching"
        const val SECRET = "ssh-private-key"
        const val SHOWN = "ssh-public-key"
        const val ALIAS = "wattrouter-reaching"
    }
}
