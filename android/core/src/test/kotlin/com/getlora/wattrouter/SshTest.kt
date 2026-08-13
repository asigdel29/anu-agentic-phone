// SshTest.kt: that the line a forge is given is one a forge can read.
//
// History
//   2026-08-13  A. Sigdel  Created with #467.
//
// On the JVM, which is the whole reason Ssh.kt encodes with java.util.Base64
// and generates in memory rather than in the AndroidKeyStore: nothing in it
// touches the platform, so the encoding can be checked here rather than on an
// emulator. Sealing the private half does need a device, and that is Reaching's
// half rather than this one.
//
// The test to read first is the one that decodes the line back into its fields.
// The format is four length-prefixed byte strings and there is no library here
// that would have caught getting the prefixes wrong; a forge would simply
// refuse the paste, on a screen nobody is watching from a test.

package com.getlora.wattrouter

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshTest {
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        .generateKeyPair().public as RSAPublicKey

    /** The fields of an ssh public key blob, in order. */
    private fun fields(line: String): List<ByteArray> {
        val blob = Base64.getDecoder().decode(line.split(" ")[1])
        val read = mutableListOf<ByteArray>()
        var at = 0
        while (at < blob.size) {
            var size = 0
            repeat(4) { size = (size shl 8) or (blob[at++].toInt() and 0xff) }
            read += blob.copyOfRange(at, at + size)
            at += size
        }
        return read
    }

    @Test
    fun theLineIsTheThreeThingsAForgeReadsOffIt() {
        val (kind, exponent, modulus) = fields(openssh(key, "phone"))

        assertEquals("ssh-rsa", String(kind))
        assertEquals(key.publicExponent, BigInteger(exponent))
        assertEquals(key.modulus, BigInteger(modulus))
    }

    @Test
    fun theKindIsSaidTwiceAndTheCommentOnceMore() {
        // authorized_keys is three fields separated by spaces, and the first
        // repeats what the blob says. A forge that reads the outer one and a
        // server that reads the inner one disagreeing is a key that pastes and
        // never works.
        val words = openssh(key, "phone").split(" ")

        assertEquals(3, words.size)
        assertEquals("ssh-rsa", words[0])
        assertEquals("phone", words[2])
    }

    @Test
    fun aModulusIsNotPaddedTwice() {
        // BigInteger.toByteArray already prefixes the zero byte a positive
        // number with a high bit set needs, which is what an ssh mpint wants.
        // Adding another is the classic mistake here and it is invisible: the
        // line still base64s, and the server reads a different key.
        val modulus = fields(openssh(key, "phone"))[2]

        assertEquals(0, modulus[0].toInt())
        assertTrue("$modulus", modulus[1].toInt() and 0x80 != 0)
        assertEquals(key.modulus.bitLength() / 8 + 1, modulus.size)
    }

    @Test
    fun theKeyItMakesIsTheOneItShows() {
        val made = Ssh.make()!!

        assertTrue(made.secret, made.secret.startsWith("-----BEGIN PRIVATE KEY-----\n"))
        assertTrue(made.secret, made.secret.endsWith("-----END PRIVATE KEY-----\n"))
        assertTrue(made.shown, made.shown.startsWith("ssh-rsa "))
    }

    @Test
    fun theSecretIsWrappedTheWayEveryReaderOfPemExpects() {
        // Unwrapped base64 is still PEM to some readers and not to others, and
        // the one that matters here is the OpenSSL behind libssh2.
        val body = Ssh.make()!!.secret.lines().drop(1).dropLast(2)

        assertTrue("$body", body.size > 1)
        assertTrue(body.toString(), body.dropLast(1).all { it.length == 64 })
    }
}
