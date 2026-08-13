// Ssh.kt: a key made on the phone, and the half of it anybody may see.
//
// History
//   2026-08-13  A. Sigdel  Created with #467.
//
// Contents
//   Keypair   A private key and the line to paste into a forge.
//   openssh   Encoding a public key the way a forge expects to read one.
//   Ssh       Making one.
//
// docs/decisions/pushing-from-a-phone.md decided the shape and this is only the
// making of it: generated in the process that will use it, never pasted, and
// only the public half ever shown. A key that arrives by paste has been in a
// clipboard, and on this phone the clipboard is something the agent can read.
//
// RSA rather than Ed25519, which is the one decision here the record does not
// already make. KeyPairGenerator has no Ed25519 below API 33 and this module's
// floor is 29, so an Ed25519 key would be a key most of the supported range
// cannot make; 3072 bits is what every forge still accepts and what OpenSSH
// itself defaults to. What that costs is a longer line to paste and nothing
// else: forges sign with rsa-sha2-256 or rsa-sha2-512 now, and the SHA-1
// signature scheme they deprecated is a property of the handshake rather than
// of the key.
//
// The private half leaves here as PKCS#8, which is what Java can encode and
// what the OpenSSL behind libssh2 reads. The public half leaves as the one line
// a forge's paste box expects, which nothing in Java encodes at all: the format
// is a length-prefixed name and two length-prefixed integers, base64ed, and it
// is written out here.
//
// java.util.Base64 rather than android.util.Base64, which Keystore.kt uses. It
// has been on Android since API 26 against a floor of 29, and it is what lets
// every line here run on the JVM: Credential.kt's reasoning, that the part with
// a bug in it should not be the part only an emulator reaches. Nothing in this
// file touches the platform, so nothing in it needs a device.

package com.getlora.wattrouter

import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * A key made here.
 *
 * @property secret the private half, PEM, to be sealed and never shown.
 * @property shown the public half, one line, to be pasted into a forge.
 */
data class Keypair(val secret: String, val shown: String)

/**
 * The one line a forge expects, from the two numbers an RSA public key is.
 *
 * `ssh-rsa` then the exponent then the modulus, each length-prefixed with four
 * big-endian bytes, the lot base64ed. `BigInteger.toByteArray` already answers
 * two's complement big-endian with the leading zero byte a positive number
 * needs, which is exactly what an ssh mpint is, so nothing here re-pads it.
 */
internal fun openssh(key: RSAPublicKey, comment: String): String {
    val body = ByteArrayOutputStream()
    for (field in listOf("ssh-rsa".toByteArray(), key.publicExponent.toByteArray(), key.modulus.toByteArray())) {
        body.write(field.size ushr 24 and 0xff)
        body.write(field.size ushr 16 and 0xff)
        body.write(field.size ushr 8 and 0xff)
        body.write(field.size and 0xff)
        body.write(field)
    }
    return "ssh-rsa ${Base64.getEncoder().encodeToString(body.toByteArray())} $comment"
}

/** Making a key, here, once. */
internal object Ssh {
    /** Bits. OpenSSH's own default, and above every forge's floor. */
    private const val BITS = 3072

    /**
     * What the public line is tagged with.
     *
     * A forge shows this beside the key in its settings, so it says which phone
     * rather than which person: it is the only part of a key that is a label,
     * and a label naming somebody is one this repository would then be storing.
     */
    private const val COMMENT = "wattrouter-on-this-phone"

    /**
     * Make one.
     *
     * Not in the AndroidKeyStore, which is the surprising half. A key generated
     * there cannot be exported, and libssh2 signs with the key itself rather
     * than through a callback, so a key the process cannot read is a key it
     * cannot push with. It is generated in memory and the private half is
     * sealed by [Keystore] instead, which is the same protection at rest and
     * an honest account of what it is not: hardware-backed, unextractable.
     *
     * @return the pair, or null if the platform refused to generate one.
     */
    fun make(): Keypair? = runCatching {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(BITS)
        val pair = generator.generateKeyPair()
        Keypair(
            secret = pem(pair.private.encoded),
            shown = openssh(pair.public as RSAPublicKey, COMMENT),
        )
    }.getOrNull()

    /** PKCS#8 in the wrapper OpenSSL reads, wrapped at 64 characters as PEM is. */
    private fun pem(encoded: ByteArray): String =
        "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getEncoder().encodeToString(encoded).chunked(64).joinToString("\n") +
            "\n-----END PRIVATE KEY-----\n"
}
