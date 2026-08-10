// DecisionFromCoreTest.kt: the decoder, against what the core actually says.
//
// History
//   2026-08-08  A. Sigdel  Created.
//
// DecisionTest reads envelopes written by hand, which is where the awkward
// shapes live. This is the other half and the one that goes stale: the core is
// free to change its envelope, and hand-written JSON would keep passing while
// nothing decoded a real answer any more.
//
// On a device because it opens a core, which needs the library.

package com.getlora.wattrouter

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionFromCoreTest {

    @Test
    fun whatTheCoreAnswersIsWhatTheDecoderReads() {
        val core = requireNotNull(Core.open("nw-test")) { "the core did not open" }
        core.use {
            val body = Conversation().apply { append(Message.user("what time is it")) }.body()

            val decision = requireNotNull(Decision.from(it.decide(body))) {
                "a real answer did not decode"
            }

            assertTrue("no tier: $decision", decision.tier.isNotEmpty())
            assertTrue("no reason: $decision", decision.reason.isNotEmpty())
            // Every tier has at least one model behind it, or routing to it
            // would be routing to nothing.
            assertTrue("empty chain: $decision", decision.chain.isNotEmpty())
            assertNotNull(decision.chain.first().model)
        }
    }
}
