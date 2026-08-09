// AndroidContacts.kt — the address book as the provider holds it.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Two queries rather than one. Selecting from Data and folding by contact id is
// the shorter way to write this and it silently drops anybody with no phone row
// and no email row — and those are people Contacts.kt renders on purpose, saying
// what is missing, because an entry that vanishes reads as somebody who is not
// in the address book. So: Contacts for who matches, Data for how to reach the
// ones that did.
//
// LIKE is the other thing. A name carrying % or _ is a wildcard to SQLite, so
// looking up "50%" matches rows nobody asked about — and "%" on its own matches
// every contact, which is the single answer Contacts.kt exists to prevent. The
// escaping belongs here because this is the layer that knows a name becomes SQL.

package com.getlora.wattrouter.app

import android.content.Context
import android.provider.ContactsContract
import com.getlora.wattrouter.Directory
import com.getlora.wattrouter.Person
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A name, as a `LIKE` pattern that matches only itself.
 *
 * The backslash goes first: escaping it after the wildcards would escape the
 * backslashes this function had just added.
 */
internal fun asPattern(name: String): String =
    "%" + name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

/** The address book on this phone. */
class AndroidContacts(private val context: Context) : Directory {

    override suspend fun find(name: String, most: Int): List<Person> =
        // Off the main thread on this side of the seam: a tool asked to know
        // which thread it is on is a tool that will one day be wrong.
        withContext(Dispatchers.IO) {
            val named = LinkedHashMap<Long, String>()
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, NAME),
                "$NAME LIKE ? ESCAPE '\\'",
                arrayOf(asPattern(name)),
                "$NAME ASC",
            )?.use { row ->
                while (row.moveToNext() && named.size < most) {
                    // Unnamed rather than blank. A contact stored as a number
                    // with no name renders as an empty line otherwise, which
                    // reads as a fault rather than as somebody unnamed.
                    named[row.getLong(0)] =
                        row.getString(1)?.takeIf { it.isNotBlank() } ?: "unnamed"
                }
            }
            if (named.isEmpty()) return@withContext emptyList()

            val phones = mutableMapOf<Long, MutableList<String>>()
            val emails = mutableMapOf<Long, MutableList<String>>()
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE, VALUE),
                // The ids are interpolated because a bind list cannot be. They
                // are the provider's own longs, read out of the first query and
                // never anything a model wrote, so there is nothing to escape —
                // which is not true one line above, and is why that one binds.
                "${ContactsContract.Data.CONTACT_ID} IN (${named.keys.joinToString(",")}) " +
                    "AND ${ContactsContract.Data.MIMETYPE} IN (?,?)",
                arrayOf(PHONE, EMAIL),
                null,
            )?.use { row ->
                while (row.moveToNext()) {
                    val value = row.getString(2)?.trim().orEmpty()
                    if (value.isEmpty()) continue
                    val into = if (row.getString(1) == PHONE) phones else emails
                    into.getOrPut(row.getLong(0)) { mutableListOf() }.add(value)
                }
            }

            // The name order, not the data order. Contacts was sorted; Data was
            // not, and its rows arrive grouped by whatever the provider likes.
            named.map { (id, who) ->
                Person(who, phones[id].orEmpty(), emails[id].orEmpty())
            }
        }

    private companion object {
        // DISPLAY_NAME_PRIMARY rather than DISPLAY_NAME: the primary is the one
        // the person's own contacts app shows, given a display-order setting.
        val NAME: String = ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        val VALUE: String = ContactsContract.Data.DATA1
        val PHONE: String = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
        val EMAIL: String = ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
    }
}
