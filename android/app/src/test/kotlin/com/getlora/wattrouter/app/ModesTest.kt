// ModesTest.kt — what a saved mode reads back as, and what is offered.
//
// History
//   2026-08-10  A. Sigdel  Created with #558.
//
// On the JVM. The store itself is SharedPreferences and needs a device; what
// does not is the decision either side of it — what an unknown string means,
// and which modes a person is shown. Both are the kind of thing that looks
// obviously right and is wrong on an upgrade.

package com.getlora.wattrouter.app

import com.getlora.wattrouter.Autonomy
import com.getlora.wattrouter.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModesTest {

    @Test
    fun nothingSavedIsAuto() {
        // What this app has always done, so an install that predates the
        // setting behaves as it did.
        assertEquals(Autonomy.AUTO, modeFrom(null))
    }

    @Test
    fun aModeThisBuildDoesNotKnowIsAuto() {
        // A later build's mode, or a preferences file somebody edited. Falling
        // back leaves the phone working; throwing would strand it at launch.
        assertEquals(Autonomy.AUTO, modeFrom("SUPERVISED"))
        assertEquals(Autonomy.AUTO, modeFrom(""))
    }

    @Test
    fun everyModeSurvivesBeingWrittenDown() {
        // The round trip Modes.now makes, held here rather than assumed: the
        // setter writes `name` and the reader matches on it, and a change to
        // either alone silently resets everybody to Auto.
        Autonomy.entries.forEach { assertEquals(it, modeFrom(it.name)) }
    }

    @Test
    fun planIsNotOfferedWhileItDoesNothing() {
        // It would behave exactly like Auto, and a picker with a setting that
        // does nothing teaches somebody the picker does not work — which is a
        // lesson they keep after it starts working.
        assertTrue("$shown", Autonomy.PLAN !in shown)
        assertEquals(listOf(Autonomy.AUTO, Autonomy.ASK), shown)
    }

    @Test
    fun everyModeHasWordsWhetherOrNotItIsOffered() {
        // Including Plan. The day it is added to `shown` is not the day to
        // discover it has no label, and the resource ids are all distinct.
        val words = Autonomy.entries.flatMap { listOf(labelOf(it), meaningOf(it)) }
        assertEquals(words.size, words.toSet().size)
    }

    @Test
    fun aQuestionReadsAsAQuestion() {
        // The whole of the wording: the verb and the thing, and a question
        // mark. Confirmed composes the two halves, and this is where they meet.
        assertEquals("tap send?", wording(Intent("tap", "send")))
        assertEquals("open com.android.deskclock?", wording(Intent("open", "com.android.deskclock")))
    }
}
