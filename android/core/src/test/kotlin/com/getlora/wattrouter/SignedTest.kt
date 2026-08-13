// SignedTest.kt: what gets signed, when it is read, and what is passed through.
//
// History
//   2026-08-11  A. Sigdel  Created with #636.
//
// On the JVM against a recording Worktree. Whether the two keys reach git is
// git.rs's to prove and RepositoryOnDeviceTest's to prove on a device; what is
// under test here is the ordering and the reading, which is where a decorator
// goes wrong.

package com.getlora.wattrouter

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every call, in the order it arrived. */
private class Order : Worktree {
    val calls = mutableListOf<String>()

    override fun init() = "".also { calls += "init" }

    override fun identify(name: String, email: String) =
        "".also { calls += "identify $name <$email>" }

    override fun head() = "".also { calls += "head" }

    override fun status() = "".also { calls += "status" }

    override fun add(paths: List<String>) = "".also { calls += "add" }

    override fun commit(message: String) =
        """{"ok":"abc1234"}""".also { calls += "commit $message" }

    override fun remoteSet(name: String, url: String) =
        "".also { calls += "remoteSet $name $url" }

    override fun fetch(name: String) = "".also { calls += "fetch $name" }

    override fun push(remote: String, branch: String) =
        "".also { calls += "push $remote $branch" }

    override fun pull(remote: String, branch: String) =
        "".also { calls += "pull $remote $branch" }
}

class SignedTest {
    @Test
    fun aCommitSaysWhoMadeItFirst() {
        // Before, not after. A commit is not editable afterwards, so a name
        // written second is a name on the next commit rather than this one.
        val order = Order()

        Signed(order) { Who("Ada", "ada@example.com") }.commit("Add a note")

        assertEquals(
            listOf("identify Ada <ada@example.com>", "commit Add a note"),
            order.calls,
        )
    }

    @Test
    fun nobodyNamedLeavesTheRefusalToTheCore() {
        // Rather than refusing here. The core's words say which two keys are
        // missing and whose job it is to set them, and a second sentence
        // composed here would be a second copy of that going stale.
        val order = Order()

        Signed(order) { null }.commit("Add a note")

        assertEquals(listOf("commit Add a note"), order.calls)
    }

    @Test
    fun theIdentityIsReadPerCommitRatherThanHeld() {
        // The driver is built once and kept, so an identity captured where the
        // tools are assembled would be whichever was set at launch. Somebody
        // correcting a misspelt address means this commit.
        val order = Order()
        var who: Who? = Who("Ada", "ada@exmaple.com")
        val signed = Signed(order) { who }

        signed.commit("first")
        who = Who("Ada", "ada@example.com")
        signed.commit("second")

        assertEquals("identify Ada <ada@exmaple.com>", order.calls[0])
        assertEquals("identify Ada <ada@example.com>", order.calls[2])
    }

    @Test
    fun readingAndStagingSignNothing() {
        // Writing a configuration entry on a read is a write nobody asked for,
        // and none of these four puts a name on anything.
        val order = Order()
        val signed = Signed(order) { Who("Ada", "ada@example.com") }

        signed.init()
        signed.head()
        signed.status()
        signed.add(listOf("a.kt"))

        assertEquals(listOf("init", "head", "status", "add"), order.calls)
    }

    @Test
    fun theAnswerIsTheCommitsRatherThanTheIdentifys() {
        // identify answers an envelope of its own, and returning that would
        // hand the tool "ok" for a commit that has not happened yet.
        val order = Order()

        val answered = Signed(order) { Who("Ada", "ada@example.com") }.commit("Add a note")

        assertEquals("""{"ok":"abc1234"}""", answered)
        assertEquals("committed abc1234", committed(answered))
    }

    @Test
    fun sayingWhoDirectlyIsPassedThroughRatherThanIntercepted() {
        // A caller naming somebody on purpose is not the caller this exists
        // for, and doubling the call would write the same two keys twice.
        val order = Order()

        Signed(order) { Who("Ada", "ada@example.com") }.identify("Grace", "grace@example.com")

        assertEquals(listOf("identify Grace <grace@example.com>"), order.calls)
    }

    @Test
    fun whatWasTypedIsLeftForTheCoreToJudge() {
        // Neither trimmed nor refused here. The core trims both and refuses a
        // blank on either side, and a second copy of that rule is one that
        // disagrees with the deciding copy the day either changes.
        val order = Order()

        Signed(order) { Who("  Ada  ", "   ") }.commit("Add a note")

        assertEquals("identify   Ada   <   >", order.calls[0])
        assertEquals("the commit still happens, and the core refuses it", 2, order.calls.size)
    }
}
