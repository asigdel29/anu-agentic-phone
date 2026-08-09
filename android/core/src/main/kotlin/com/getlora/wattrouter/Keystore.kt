// Keystore.kt — a secret at rest, under a key the app never sees.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Hand-rolled rather than androidx.security:security-crypto. That library's
// EncryptedSharedPreferences was deprecated at 1.1.0-alpha07 — strict-mode
// violations on the main thread, and keyset corruption on some manufacturers'
// devices — and the replacement it points at is DataStore plus Tink. Tink is a
// dependency for what is below: one key, one transformation, two methods.
//
// What the Android Keystore actually buys is that the key material never enters
// this process. It is generated in, and used from, hardware-backed storage where
// there is any; `seal` and `open` hand bytes across rather than holding a key.
// Rolling the encryption by hand would not have that property, and it is the
// only property that matters here.

package com.getlora.wattrouter

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypting a short string under a key held by the system. */
internal object Keystore {
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** GCM's authentication tag length, in bits. The maximum, and the default. */
    private const val TAG_BITS = 128

    /** GCM's nonce, in bytes. Twelve is the size the construction is defined for. */
    private const val NONCE_BYTES = 12

    /**
     * Encrypt [plain] under [alias], creating the key if there is none.
     *
     * @return the nonce and ciphertext together, Base64, or null if the keystore
     *   refused — which happens on a device with no secure hardware configured,
     *   and is a state to report rather than to work around.
     */
    fun seal(alias: String, plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyFor(alias))

        // The nonce is stored beside the ciphertext rather than derived. GCM
        // needs a distinct one per encryption under the same key, and the
        // provider generates it; keeping it is the only way to decrypt later.
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray())
        Base64.encodeToString(sealed, Base64.NO_WRAP)
    }.getOrNull()

    /**
     * Decrypt what [seal] produced.
     *
     * @return the original string, or null if the key is gone or the bytes do
     *   not authenticate. Both mean the same thing to a caller — there is no
     *   credential here — and neither is worth distinguishing to one.
     */
    fun open(alias: String, sealed: String): String? = runCatching {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyFor(alias),
            GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES),
        )
        String(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES))
    }.getOrNull()

    /** Forget the key, which makes everything sealed under it undecryptable. */
    fun discard(alias: String): Boolean = runCatching {
        KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias)
    }.isSuccess

    private fun keyFor(alias: String): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately not setUserAuthenticationRequired. A turn can be
                // started by a share intent or a tile while the phone is locked,
                // and a credential that needs an unlock to read would make those
                // fail in a way nothing on screen could explain.
                .build(),
        )
        return generator.generateKey()
    }
}
