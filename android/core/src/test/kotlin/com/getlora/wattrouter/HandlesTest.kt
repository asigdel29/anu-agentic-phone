// HandlesTest.kt: what survives the wire, and what is refused at it.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM. The round trip is the whole contract: whatever a reading put in a
// token, resolve has to get back, or the token is a description of a node other
// than the one it was made from.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandlesTest {
    private fun roundTrip(handle: Handle) = assertEquals(handle, decode(encode(handle)))

    @Test
    fun anOrdinaryHandleIsReadable() {
        assertEquals(
            "h:send|button|Send||0",
            encode(Handle(viewId = "send", role = "button", text = "Send")),
        )
    }

    @Test
    fun everyFieldSurvivesTheRoundTrip() {
        roundTrip(Handle("send", "button", "Send", "Send the message", 3))
        roundTrip(Handle(viewId = "send", role = "button"))
        roundTrip(Handle(text = "Rent is due", role = "text", siblingIndex = 2))
        roundTrip(Handle(description = "Attach a file", role = "button"))
    }

    @Test
    fun aSeparatorInsideALabelDoesNotBecomeAField() {
        // Labels contain pipes. "Inbox | 3 unread" read as fields would be a
        // handle for something else entirely, and it would resolve.
        roundTrip(Handle(role = "text", text = "Inbox | 3 unread"))
        roundTrip(Handle(role = "text", text = "back\\slash"))
        roundTrip(Handle(role = "text", text = "both \\| of them"))
    }

    @Test
    fun aTrailingEscapeIsATokenThatWasCut() {
        // Rather than a last field to read. A model that truncated one should
        // be told it is unusable, not handed a handle missing its tail.
        assertNull(decode("h:send|button|Send||0\\"))
    }

    @Test
    fun somethingThatIsNotATokenIsNotAHandle() {
        assertNull(decode(null))
        assertNull(decode(""))
        assertNull(decode("send"))
        assertNull(decode("the Send button"))
        // No mark: prose in a tool call must never read as a handle.
        assertNull(decode("send|button|Send||0"))
    }

    @Test
    fun aTokenOfTheWrongLengthIsRefused() {
        // Cut short, or from something that is not this build.
        assertNull(decode("h:send|button"))
        assertNull(decode("h:send|button|Send||0|extra"))
    }

    @Test
    fun anIndexThatIsNotOneIsRefused() {
        // Assembled with a default instead, this would resolve, against the
        // first of however many nodes matched.
        assertNull(decode("h:send|button|Send||"))
        assertNull(decode("h:send|button|Send||first"))
        assertNull(decode("h:send|button|Send||-1"))
    }

    @Test
    fun aTokenTheModelPaddedIsStillReadable() {
        // Models put tokens in quotes and on their own lines. Trimming is
        // forgiveness the strictness above can afford.
        assertEquals(
            Handle("send", "button", "Send", null, 0),
            decode("  h:send|button|Send||0  "),
        )
    }

    @Test
    fun aHandleThatNamesNothingStillTravels() {
        // encode does not judge: isFindable is resolve's refusal to make, and
        // making it twice would put the same rule in two places.
        val nothing = Handle(role = "button", siblingIndex = 4)

        assertEquals(nothing, decode(encode(nothing)))
        assertEquals(Resolution.Unusable, resolve(Seen(role = "window"), decode(encode(nothing))!!))
    }
}
