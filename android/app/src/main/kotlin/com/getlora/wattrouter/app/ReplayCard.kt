// ReplayCard.kt: the screens a turn left behind, in order.
//
// History
//   2026-08-11  A. Sigdel  Created with #598.
//
// Contents
//   ReplayCard  The steps, as a strip somebody scrolls.
//
// The transcript carries what a turn did as prose: a name and a result per
// tool. That is what the agent says it did. This is what the phone looked like
// afterwards, which is a different claim and the one somebody checks.
//
// It sits under the conversation rather than inside it, and appears only once a
// turn has finished acting. A card that grew mid-turn would be a thing moving
// under the eye of somebody trying to read the answer, and #598's whole purpose
// is review after the fact.
//
// Horizontal, because time is horizontal here and the alternative is a column
// of full-width screenshots that pushes the answer off the page. Each is a
// thumbnail with its sentence under it, so the strip reads as a sequence rather
// than as a gallery.
//
// A step with no picture still gets a tile. What was done is the claim; the
// picture is the evidence, and a missing one should read as evidence absent
// rather than as a step that did not happen.

package com.getlora.wattrouter.app

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image as Picture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.getlora.wattrouter.Acted

/**
 * A data URL back into something Compose can draw.
 *
 * Null for anything that will not decode, which is treated as a step with no
 * picture rather than as an error: a tile saying what was done is still worth
 * showing, and a turn is not the place to report that a PNG was malformed.
 */
internal fun pictured(url: String) = runCatching {
    val encoded = url.substringAfter("base64,", missingDelimiterValue = "")
    if (encoded.isEmpty()) return@runCatching null
    val bytes = Base64.decode(encoded, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

/**
 * What the turn did, with the screen each step left behind.
 *
 * @param steps oldest first, as [com.getlora.wattrouter.Replay] keeps them.
 *   Empty draws nothing at all: a card headed "what it did" over no steps is a
 *   card saying the agent did nothing, which is a claim rather than an absence.
 */
@Composable
fun ReplayCard(steps: List<Acted>) {
    if (steps.isEmpty()) return

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("What it did", style = MaterialTheme.typography.labelMedium)

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                steps.forEach { step -> Tile(step) }
            }
        }
    }
}

@Composable
private fun Tile(step: Acted) {
    // Decoded once per step rather than per frame. A strip of six PNGs decoded
    // on every recomposition is six decodes per scroll frame, which is the kind
    // of thing that reads as the phone being slow rather than as this card.
    val picture = remember(step.screen) { step.screen?.let { pictured(it.url) } }

    Column(modifier = Modifier.width(TILE)) {
        if (picture != null) {
            Picture(
                bitmap = picture.asImageBitmap(),
                // Named for what it is rather than described. A screen reader
                // reading out the whole of a screenshot is not what somebody
                // asked for, and the sentence under it says which step it is.
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            step.did,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Wide enough to make out a screen, narrow enough that three fit on a phone. */
private val TILE = 96.dp
