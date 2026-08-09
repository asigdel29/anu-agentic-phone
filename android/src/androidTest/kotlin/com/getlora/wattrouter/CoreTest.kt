// CoreTest.kt — the library, actually loaded.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// Everything before this checked that two files agree about a name. This runs on
// a device, so it checks the thing those names were for: System.loadLibrary
// finds the .so, the entry points are reachable through it, and a decision comes
// back as the envelope Swift already decodes.
//
// Instrumented rather than JVM, and it has to be: the .so is built for
// aarch64-linux-android and a JVM test runs on the host, where it will not load
// at all. That is why #310 and #312 could not make this claim.
//
// The credential is a placeholder. Deciding is local — classify, score, apply
// policy — and nothing here reaches the provider, so what the key has to be is
// present rather than valid.

package com.getlora.wattrouter

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreTest {
    private fun opened(): Core = requireNotNull(Core.open("android-test")) { "the core did not open" }

    @Test
    fun theLibraryLoadsAndTheCoreOpens() {
        // The claim nothing before this could make. A wrong symbol name, a
        // missing ABI directory or an unbuilt .so all fail here and nowhere
        // earlier.
        opened().use { assertNotNull(it) }
    }

    @Test
    fun anEmptyCredentialIsRefusedRatherThanReachingTheProvider() {
        // It would arrive as a 401 from somewhere else entirely, which is the
        // failure people spend an afternoon on.
        assertNull(Core.open(""))
        assertNull(Core.open("   "))
    }

    @Test
    fun aDecisionCrossesAsTheEnvelopeSwiftAlreadyDecodes() {
        opened().use { core ->
            val chat = Conversation()
            chat.append(Message.user("hello there"))

            val answered = requireNotNull(core.decide(chat.body())) { "no answer at all" }
            val ok = Json.parseToJsonElement(answered).jsonObject["ok"]!!.jsonObject

            assertEquals("mid", ok["tier"]!!.jsonPrimitive.content)
            assertEquals("unscored", ok["reason"]!!.jsonPrimitive.content)
            // Absent rather than -1: a number meaning "no number" is one a caller
            // compares against a threshold.
            assertNull(ok["score"])
            // A tier is a role; what answers is the chain, and a caller holding
            // only the tier cannot dispatch.
            assertTrue(ok["chain"]!!.jsonArray.isNotEmpty())
        }
    }

    @Test
    fun somethingThatIsNotARequestCrossesAsAnError() {
        opened().use { core ->
            val answered = requireNotNull(core.decide("not json"))
            val read = Json.parseToJsonElement(answered).jsonObject

            assertNull(read["ok"])
            assertTrue(read["error"]!!.jsonPrimitive.content.isNotEmpty())
        }
    }

    @Test
    fun aClosedCoreRefusesRatherThanUsingAFreedPointer() {
        // The one mistake the handle allows, and the reason close clears it.
        val core = opened()
        core.close()

        assertThrows(IllegalStateException::class.java) { core.decide("{}") }
        // And closing again is a no-op rather than a double free.
        core.close()
    }

    @Test
    fun theBodyABuiltConversationProducesIsOneTheCoreAccepts() {
        // The two halves of #312 and this one, meeting: Conversation writes the
        // JSON, and the core is what decides whether it was right.
        opened().use { core ->
            val chat = Conversation(system = "be brief")
            chat.append(Message.user("""he said "no" and left"""))

            val answered = requireNotNull(core.decide(chat.body(), session = "s"))
            assertFalse(Json.parseToJsonElement(answered).jsonObject.containsKey("error"))
        }
    }

    private fun <T : Throwable> assertThrows(type: Class<T>, body: () -> Unit) {
        try {
            body()
        } catch (thrown: Throwable) {
            assertTrue("threw ${thrown::class.java}", type.isInstance(thrown))
            return
        }
        throw AssertionError("did not throw ${type.simpleName}")
    }
}
