// CopiedDeviceTest.kt: a real screen, through every rule in Phase 3.
//
// History
//   2026-08-09  A. Sigdel  Created.
//   2026-08-12  A. Sigdel  Read a screen this test put in front, #572.
//
// The claim the JVM cannot make. Everything from #400 onwards was written
// against an eleven-line fake, which is the right way to check the rules and no
// evidence at all that the framework's tree becomes one.
//
// UiAutomation rather than an AccessibilityService: it gives the same tree
// through the same API and needs no service enabled, so this runs in the
// ordinary instrumented suite rather than behind a settings toggle nobody can
// flip from a test.

package com.getlora.wattrouter.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.getlora.wattrouter.Generations
import com.getlora.wattrouter.Viewing
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopiedDeviceTest {
    private val roles = setOf(
        "view", "button", "field", "text", "image",
        "toggle", "choice", "list", "scroll", "web",
    )

    /**
     * A screen with something on it. Null if none arrived.
     *
     * Home first, and a tree rather than a root, which is #572. The first
     * non-null root was taken before, and inside the suite that is whatever the
     * previous test left behind: an activity on its way out answers a window of
     * about three nodes, every rule below then measures it, and "nothing
     * survived pruning" reports a finding about the launcher that nobody made.
     *
     * Home is what the objection to clearing the screen does not cover. It
     * papers over nothing here: what is in front is incidental to this test,
     * which asks whether the framework's tree becomes one the rules read, and
     * the launcher is a real screen nobody wrote as much as anything else is.
     * The two tests where what is in front *is* the finding start their own
     * activity and assert about that.
     */
    private fun rootNow(): AccessibilityNodeInfo? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        repeat(WAITS) {
            val root = automation.rootInActiveWindow
            // Copied rather than counted on the framework node: a root whose
            // children have not arrived yet copies to a tree with none, and
            // that is the state worth waiting through rather than measuring.
            if (root != null && snapshot(root)?.children?.isNotEmpty() == true) return root
            Thread.sleep(PAUSE)
        }
        return null
    }

    @Test
    fun aRealScreenCopiesIntoTheShapeTheRulesRead() {
        val root = rootNow()
        assertNotNull(
            "no screen with anything on it arrived in ${WAITS * PAUSE}ms, so " +
                "nothing here was measured",
            root,
        )

        val tree = snapshot(root)
        assertNotNull("the root copied to nothing", tree)

        // Deeper than one: a screen that copied its root and lost every child
        // would pass every assertion below and be useless.
        assertTrue("a screen with no children", tree!!.children.isNotEmpty())
    }

    @Test
    fun everyRoleOnARealScreenIsOneWeNamed() {
        // The mapping is checked on the JVM against names written by hand.
        // This is the other half: that the names a device actually produces
        // fall inside it, rather than arriving as something unmapped.
        val tree = snapshot(rootNow())
        assertNotNull(tree)

        val seen = mutableSetOf<String>()
        val pending = ArrayDeque(listOf(tree!!))
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            seen += node.role
            pending.addAll(node.children)
        }

        assertTrue("$seen", seen.isNotEmpty())
        assertTrue("unmapped roles: ${seen - roles}", roles.containsAll(seen))
    }

    @Test
    fun theRulesRunOverItAndLeaveSomethingWorthReading() {
        // prune, shapeOf and the generation, over a tree nobody wrote. A real
        // screen is hundreds of nodes and most of them are layout, so the
        // interesting assertion is that what survives is much smaller and is
        // not nothing.
        val tree = snapshot(rootNow())
        assertNotNull(tree)

        val viewing = Viewing(Generations("device-test"))
        val reading = viewing.read(tree!!)

        // A screen with children went in, so an empty reading is the rules
        // removing everything rather than there having been nothing.
        assertTrue(
            "nothing survived pruning, over a screen of ${countOf(tree)} node(s)",
            reading.seen.isNotEmpty(),
        )
        assertTrue("${reading.generation}", reading.generation.counter >= 1)
        assertTrue(
            "pruning kept everything, which means it kept nothing out",
            reading.seen.size < countOf(tree),
        )
        assertTrue(
            "every sighting should be findable again",
            reading.seen.all { it.handle.isFindable || !it.isClickable },
        )
    }

    private fun countOf(root: com.getlora.wattrouter.Node): Int {
        var count = 0
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            count++
            pending.addAll(pending.removeFirst().children)
        }
        return count
    }

    private companion object {
        /** Ten seconds, as the two tests that start an activity of their own. */
        const val WAITS = 40
        const val PAUSE = 250L
    }
}
