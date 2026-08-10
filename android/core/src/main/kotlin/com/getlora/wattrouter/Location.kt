// Location.kt: where the phone is, and how much of that to claim.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// Contents
//   Place         A fix: a point, a radius, and when it was taken.
//   Whereabouts   Where fixes come from, as a seam.
//   LocationTool  What the model calls.
//
// Two things here are not plumbing.
//
// Precision is not accuracy. A coarse fix is a circle a kilometre or two across,
// and rendered to six decimal places it reads as a doorstep, so the coordinates
// are rounded to what the reported radius supports, and the radius is said
// beside them. AndroidAsking asks for coarse deliberately, so the wide case is
// the normal one rather than the exception.
//
// And a fix has a time that is not the time it was asked for. Providers answer
// instantly with whatever they last cached, so "where am I" can be answered
// truthfully with a car park somebody left an hour ago. The age is rendered, and
// past a threshold the answer says so rather than letting it pass as current.

package com.getlora.wattrouter

import java.util.Locale
import kotlin.math.roundToLong

/** A fix. */
data class Place(
    val latitude: Double,
    val longitude: Double,
    /** Metres. How much of a circle the point really is. */
    val accuracy: Float,
    /** Seconds. When the fix was taken, not when it was asked for. */
    val at: Long,
)

/** Where fixes come from. */
interface Whereabouts {
    /**
     * The phone's position, or null if there is none to be had.
     *
     * # Rely
     * Called from the turn loop with the capability already obtained. May wait
     * on hardware, so it belongs off the main thread, and the conformance moves it
     * there rather than the caller.
     *
     * @return null when the phone cannot get a fix, which is an ordinary
     *   outcome indoors and when location is switched off for the whole device.
     */
    suspend fun current(): Place?
}

/** Say where the phone is. */
class LocationTool(
    private val whereabouts: Whereabouts,
    private val permission: Permission,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : Tool {
    override val name = "where_am_i"

    override val purpose =
        "Find out roughly where the person is. Use it when the answer depends on " +
            "that: what is nearby, what the weather is, which timezone they are " +
            "in. It is approximate on purpose, and the answer says how approximate."

    /** No arguments, and an object anyway: a provider handed a bare `{}` schema
     *  with no `properties` rejects the tool rather than the call. */
    override val schema = """{"type":"object","properties":{}}"""

    /** # Rely
     *  Obtains LOCATION, so it may put a dialog on screen and wait for somebody
     *  to answer it, and then wait again on the hardware. */
    override suspend fun run(arguments: String): String {
        try {
            permission.obtain(Capability.LOCATION)
        } catch (e: PermissionError) {
            return e.message.orEmpty()
        }

        val place = whereabouts.current()
            // Both reasons named. A model told only "no location" cannot tell
            // an indoor phone from one where location is off for everything,
            // and the second has an answer the person can act on.
            ?: return "no position was available. The phone may have no signal " +
                "indoors, or location may be switched off for the whole device " +
                "in Settings > Location."

        return describe(place, now())
    }

    companion object {
        /** Past this a fix is described as old rather than as where somebody is. */
        const val STALE = 15 * 60

        /**
         * A fix as the model reads it. Separate from [run] so the rounding and
         * the age, which are the decisions, are exercised without hardware.
         */
        fun describe(place: Place, now: Long): String {
            // Locale.ROOT, not the phone's. A region that writes a decimal
            // comma renders 53,4808, and a model reading that reads two numbers.
            val figures = "%.${decimalsFor(place.accuracy)}f"
            val point = figures.format(Locale.ROOT, place.latitude) +
                ", " + figures.format(Locale.ROOT, place.longitude)

            val age = now - place.at
            val taken = when {
                // Negative means a fix from the future, which is a clock that
                // disagrees with itself rather than something to render.
                age < 0 -> "timed oddly"
                age < 60 -> "just now"
                age < STALE -> "${age / 60} minutes ago"
                age < 2 * 3600 -> "${age / 60} minutes ago, so possibly out of date"
                else -> "${age / 3600} hours ago, so probably not where they are now"
            }

            return "$point (within ${place.accuracy.roundToLong()} m, taken $taken)"
        }

        /**
         * How many decimals a radius earns.
         *
         * A degree of latitude is about 111 km, so a decimal place is about
         * 11 km and each one after divides by ten. Printing past the point the
         * radius supports states a doorstep where the phone knows a district,
         * and a model reading it has no way to tell.
         */
        fun decimalsFor(accuracy: Float): Int = when {
            accuracy <= 10f -> 4
            accuracy <= 100f -> 3
            accuracy <= 1_000f -> 2
            else -> 1
        }
    }
}
