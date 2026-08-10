// AndroidWhereabouts.kt: a fix from the platform, and no Play Services for it.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// The milestone's plan says FusedLocationProviderClient. That class is
// play-services-location, a Play Services dependency taken for one call
// returning one coordinate, and this repository has made the same judgement
// three times and gone the other way each time: hand-written AES-GCM over the
// Keystore rather than Tink, buildJsonObject rather than the serialization
// plugin, HttpURLConnection rather than OkHttp. LocationManager costs nothing
// and, since API 31, carries FUSED_PROVIDER: the same fusion that client wraps.
//
// GPS is not asked for. With only ACCESS_COARSE_LOCATION the system coarsens a
// GPS answer anyway, so it would buy a wait and a battery for precision that is
// then thrown away.

package com.getlora.wattrouter.app

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import com.getlora.wattrouter.Place
import com.getlora.wattrouter.Whereabouts
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * How wide a circle a fix really is.
 *
 * @param has what `Location.hasAccuracy()` said.
 * @param reported what `Location.accuracy` holds, which is 0 when [has] is false,
 *   and 0 is not perfect knowledge, it is none. Reported wide so the rendering
 *   in LocationTool rounds the coordinates down rather than to a doorstep.
 */
internal fun radiusOf(has: Boolean, reported: Float): Float =
    if (has && reported > 0f) reported else UNKNOWN_RADIUS

/** Wide enough that LocationTool prints one decimal place: tens of kilometres. */
internal const val UNKNOWN_RADIUS = 50_000f

/** Where this phone thinks it is. */
class AndroidWhereabouts(private val context: Context) : Whereabouts {

    override suspend fun current(): Place? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = providerOn(manager) ?: return null

        // The permission can be revoked between LocationTool obtaining it and
        // this line, and the framework answers that with a SecurityException.
        // The window is small and real, and a dead turn is worse than the
        // no-fix sentence, which already names Settings.
        val fix = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fresh(manager, provider)
            } else {
                manager.getLastKnownLocation(provider)
            }
        } catch (_: SecurityException) {
            null
        } ?: return null

        return Place(
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracy = radiusOf(fix.hasAccuracy(), fix.accuracy),
            // Wall clock rather than elapsedRealtimeNanos. The monotonic one is
            // the better measure of age and is not a time, and LocationTool
            // subtracts this from a wall clock to render one.
            at = fix.time / 1000,
        )
    }

    /**
     * A fix asked for now rather than whatever was last cached.
     *
     * Below API 30 there is no such call and the last known one is what there
     * is, which is why LocationTool renders an age at all.
     */
    private suspend fun fresh(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { waiting ->
            val stop = CancellationSignal()
            // Cancelling the turn cancels the fix. Without this the framework
            // holds a callback into a coroutine nobody is waiting on.
            waiting.invokeOnCancellation { stop.cancel() }
            manager.getCurrentLocation(provider, stop, context.mainExecutor) { fix ->
                if (waiting.isActive) waiting.resume(fix)
            }
        }

    private fun providerOn(manager: LocationManager): String? = when {
        // The platform's own fusion, and the reason no artefact is needed. It
        // reads whatever the phone has and answers coarse permission with a
        // coarse result rather than an exception.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) ->
            LocationManager.FUSED_PROVIDER

        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
            LocationManager.NETWORK_PROVIDER

        // Location is off for the whole device, which LocationTool's no-fix
        // sentence already names as one of the two reasons there is nothing.
        else -> null
    }
}
