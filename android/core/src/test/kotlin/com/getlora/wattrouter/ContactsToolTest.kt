// ContactsToolTest.kt — what a lookup hands over, and what it will not.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM against a scripted directory. What a provider actually answers is
// the conformance's claim; every decision about how much of it leaves the phone
// is here.

package com.getlora.wattrouter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class Standing(private val answer: PermissionState) : Asking {
    var dialogs = 0

    override suspend fun state(of: Capability) = answer

    override suspend fun request(capability: Capability) = answer.also { dialogs++ }
}

private class Book(private val people: List<Person> = emptyList()) : Directory {
    var asked: String? = null

    override suspend fun find(name: String, most: Int): List<Person> {
        asked = name
        return people
    }
}

class ContactsToolTest {
    private fun tool(book: Book, asking: Standing) = ContactsTool(book, Permission(asking))

    @Test
    fun aBlankNameIsNotEverybody() = runTest {
        // A provider handed an empty pattern matches every row, which is the
        // one call this tool exists to not have. No dialog is spent on it.
        val book = Book()
        val asking = Standing(PermissionState.UNASKED)

        val said = tool(book, asking).run("""{"name":"   "}""")

        assertTrue(said, said.contains("no name was given"))
        assertEquals(0, asking.dialogs)
        assertNull("the directory should not have been read", book.asked)
    }

    @Test
    fun aRefusalIsASentenceRatherThanAThrow() = runTest {
        val said = tool(Book(), Standing(PermissionState.PERMANENTLY_DENIED))
            .run("""{"name":"Ada"}""")

        assertTrue(said, said.contains("Settings > Apps"))
    }

    @Test
    fun theNameIsTrimmedBeforeItIsUsed() = runTest {
        val book = Book(listOf(Person("Ada Lovelace")))

        tool(book, Standing(PermissionState.GRANTED)).run("""{"name":"  Ada  "}""")

        assertEquals("Ada", book.asked)
    }

    @Test
    fun somebodyIsOneEntryHoweverManyWaysThereAreToReachThem() {
        // Split by number, the same name appears twice and the model reports
        // two people who happen to share it.
        val said = ContactsTool.describe(
            "Ada",
            listOf(
                Person(
                    "Ada Lovelace",
                    phones = listOf("+44 7700 900461", "+44 20 7946 0018"),
                    emails = listOf("ada@example.com"),
                ),
            ),
        )

        assertEquals(
            "Ada Lovelace  +44 7700 900461  +44 20 7946 0018  ada@example.com",
            said,
        )
        assertEquals(1, said.lines().size)
    }

    @Test
    fun somebodyWithNoWayToReachThemIsStillThere() {
        // An entry that vanished would read as somebody who is not in the
        // address book, and the model would say so.
        assertEquals(
            "Grace Hopper  (no number or address stored)",
            ContactsTool.describe("Grace", listOf(Person("Grace Hopper"))),
        )
    }

    @Test
    fun nobodyMatchingSaysWhatWasAskedFor() {
        // "Nothing found" leaves a model unsure whether it asked wrongly or
        // asked about somebody who is not there.
        assertEquals(
            "nobody in contacts matches \"Ada\"",
            ContactsTool.describe("Ada", emptyList()),
        )
    }

    @Test
    fun moreThanFitsIsSaidRatherThanDropped() {
        val many = (1..ContactsTool.LIMIT + 2).map { Person("Person $it", listOf("07$it")) }

        val said = ContactsTool.describe("Person", many)

        assertEquals(ContactsTool.LIMIT + 1, said.lines().size)
        assertTrue(said, said.endsWith("and at least 2 more match; ask about one of them by name"))
    }
}
