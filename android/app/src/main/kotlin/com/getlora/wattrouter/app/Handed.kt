// Handed.kt — what another app shared, and what to make of it.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Inbox.swift is the iOS half and this is deliberately not a port of it. A share
// extension there is a second process with its own sandbox, so the two halves
// talk through a shared container, and three of that file's decisions exist only
// because of the gap between them: an atomic write, a read that removes, and an
// order carried in the filenames because a directory promises none.
//
// ACTION_SEND is delivered into this app's own process. There is no gap, so
// there is nothing to carry across one.
//
// What Android has instead is its own way to hand the same text over twice, and
// that is handled where the intent is read rather than here. This file is only
// the deciding, which is a function of four strings and needs no phone.

package com.getlora.wattrouter.app

/**
 * The text worth taking from a share, or null if there is none.
 *
 * @param action the intent's action. Anything but a send is somebody launching
 *   the app, which is not a share and must not seed a turn.
 * @param type the MIME type. A stream is not taken: that is a file, and there
 *   is no file tool here to hand one to. Refusing beats pasting a content URI
 *   the model cannot open and will describe as though it had.
 * @param text `EXTRA_TEXT`.
 * @param subject `EXTRA_SUBJECT`. A browser shares a page's title and its URL
 *   as two extras, and the title is half of what was meant — so it goes above
 *   the text when both are there and differ. Identical is the common case for
 *   a plain note, and repeating it reads as a mistake.
 */
internal fun handedIn(
    action: String?,
    type: String?,
    text: String?,
    subject: String?,
): String? {
    if (action != android.content.Intent.ACTION_SEND) return null
    if (type?.startsWith("text/") != true) return null

    val body = text?.trim().orEmpty()
    val title = subject?.trim().orEmpty()
    if (body.isEmpty()) {
        // A subject on its own is still something somebody meant to send.
        return title.ifEmpty { null }
    }

    return if (title.isEmpty() || title == body) body else "$title\n$body"
}
