// ReplayCardTest.kt: turning a stored picture back into one to draw.
//
// History
//   2026-08-11  A. Sigdel  Created with #598.
//
// On the JVM against `pictured` alone. The card is Compose and belongs on a
// device; what a data URL decodes to is a decision, and it is the one with a
// way to go wrong quietly: a URL that will not decode should be a step with no
// picture rather than a crash in the middle of a transcript.
//
// BitmapFactory is Android and answers null off a device, so these assert the
// parsing either side of it rather than the bitmap. What the whole path
// produces was measured on a device in #629, which is where the encoding half
// of it lives.

package com.getlora.wattrouter.app

import org.junit.Assert.assertNull
import org.junit.Test

class ReplayCardTest {

    @Test
    fun somethingThatIsNotADataUrlIsNoPicture() {
        // A transcript is not the place to report that a string was not a PNG.
        assertNull(pictured("https://example.com/shot.png"))
        assertNull(pictured(""))
        assertNull(pictured("data:image/png"))
    }

    @Test
    fun aDataUrlWithNothingAfterTheCommaIsNoPicture() {
        // The shape an empty encode takes. Decoding it gives zero bytes, and a
        // zero-byte bitmap is a tile drawn at no height.
        assertNull(pictured("data:image/png;base64,"))
    }

    @Test
    fun rubbishAfterTheCommaIsNoPictureRatherThanACrash() {
        // Base64.decode throws on malformed input, and this runs inside a
        // LazyColumn item: an exception here takes the conversation with it.
        assertNull(pictured("data:image/png;base64,!!!not base64!!!"))
    }
}
