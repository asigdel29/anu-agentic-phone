// ConnectionsStoreTest.kt: the list, written and read back for real.
//
// History
//   2026-08-10  A. Sigdel  Created with #596.
//
// On a device for CredentialStoreTest's reason turned around. There is no bug
// worth finding in SharedPreferences; there is one worth finding in what this
// file encodes into it. A list of servers is JSON under one key, and a round
// trip is the only thing that says the encoder and the decoder agree.
//
// Each case uses its own preferences file, so one test forgetting a server
// cannot delete one another is mid-way through using.

package com.getlora.wattrouter

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ConnectionsStoreTest {
    private lateinit var file: String
    private lateinit var connections: Connections

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun store(named: String) =
        context.getSharedPreferences(named, Context.MODE_PRIVATE)

    @Before
    fun open() {
        file = "connections-test-${System.nanoTime()}"
        connections = Connections(store(file))
    }

    @After
    fun clear() {
        store(file).edit().clear().commit()
    }

    @Test
    fun aFreshInstallHasNoneAndSaysSoRatherThanFailing() {
        assertEquals(emptyList<Connection>(), connections.all)
    }

    @Test
    fun oneSurvivesBeingWrittenAndReadBack() {
        assertNull(connections.add("desk", "https://tools.example.com/mcp"))

        assertEquals(
            listOf(Connection("desk", "https://tools.example.com/mcp")),
            connections.all,
        )
    }

    @Test
    fun theyKeepTheOrderTheyWereAddedIn() {
        // The order they appear in a list somebody reads, and the order their
        // tools reach ToolBox, where the first of a duplicate wins.
        connections.add("one", "https://one.example.com")
        connections.add("two", "https://two.example.com")
        connections.add("three", "https://three.example.com")

        assertEquals(listOf("one", "two", "three"), connections.all.map { it.label })
    }

    @Test
    fun aRefusedPairIsNotStored() {
        // The check is in `add` as well as at the field, so a caller cannot
        // store a pair the screen would have refused.
        assertNotNull(connections.add("desk", "http://tools.example.com"))

        assertEquals(emptyList<Connection>(), connections.all)
    }

    @Test
    fun aSecondServerCannotTakeANameAlreadyUsed() {
        connections.add("desk", "https://one.example.com")

        assertNotNull(connections.add("desk", "https://two.example.com"))
        assertEquals(1, connections.all.size)
    }

    @Test
    fun forgettingLeavesTheRest() {
        connections.add("one", "https://one.example.com")
        connections.add("two", "https://two.example.com")

        connections.forget("one")

        assertEquals(listOf("two"), connections.all.map { it.label })
    }

    @Test
    fun forgettingSomethingUnknownIsNotAFailure() {
        connections.add("one", "https://one.example.com")

        connections.forget("nothing here")

        assertEquals(1, connections.all.size)
    }

    @Test
    fun aFileSomebodyEditedLeavesThePhoneWorking() {
        // The rule `modeFrom` follows, for the same reason: a preferences file
        // that will not parse should be no servers rather than a crash on the
        // screen that lists them.
        store(file).edit().putString("servers", "not json at all").commit()

        assertEquals(emptyList<Connection>(), Connections(store(file)).all)
    }

    @Test
    fun aRowMissingAFieldIsSkippedRatherThanHalfBuilt() {
        // A connection with no endpoint cannot be reached, and one holding an
        // empty string would be listed and fail at the first call.
        val written = """[{"label":"desk"},{"label":"ok","endpoint":"https://x.example.com"}]"""
        store(file).edit().putString("servers", written).commit()

        val all = Connections(store(file)).all
        assertEquals(1, all.size)
        assertEquals("ok", all.single().label)
    }

    @Test
    fun whatIsStoredIsReadableByASecondInstance() {
        // Which is every launch. A list held in memory alone would be one that
        // survives until somebody closes the app.
        connections.add("desk", "https://tools.example.com/mcp")

        assertEquals("desk", Connections(store(file)).all.single().label)
    }
}
