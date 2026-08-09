// TurnService.kt — a turn that outlives the screen that started it.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// The driver runs a turn in the composition's scope, so backgrounding the app
// cancels it. That is the right scope for a chat and the wrong one for the
// thing #229 says Android can do and iOS cannot: TurnDriver.isLong exists over
// there to *warn* somebody, because a backgrounded app has seconds.
//
// specialUse rather than dataSync, and what-android-allows.md now says why:
// Android 15 caps dataSync at six hours in twenty-four and then calls
// onTimeout(). specialUse is uncapped and needs no store justification for a
// build nobody submits.
//
// What this buys is narrower than "keeps the turn alive". A process the system
// is not looking to reclaim keeps the turn alive; the service is how the person
// is told it is running, and where they cancel it from.

package com.getlora.wattrouter.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** Somewhere for a turn to run that is not a screen. */
class TurnService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        channel()
        startForeground(ID, notification(intent?.getStringExtra(ABOUT).orEmpty()))

        // NOT_STICKY: a turn the system killed is not one to restart from
        // nothing. The conversation it belonged to is gone, and re-running it
        // would ask a question nobody is waiting for the answer to.
        return START_NOT_STICKY
    }

    private fun channel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Running a turn",
                // Low: it is a status, not an interruption. Anything higher
                // makes a sound every time somebody asks a question.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun notification(about: String): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, TurnService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL)
            .setContentTitle(if (about.isBlank()) "Working" else about)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            // The cancel is the reason this is visible at all. A notification
            // that only says a turn is running is a notification people swipe.
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "turn"
        private const val ID = 1
        private const val STOP = "com.getlora.wattrouter.STOP_TURN"
        private const val ABOUT = "about"

        /**
         * Show that a turn is running.
         *
         * @param about what it is doing, for the notification. The person's own
         *   words rather than a tier name: "Working" tells them nothing they
         *   did not know when they pressed send.
         */
        fun begin(context: Context, about: String) {
            if (Build.VERSION.SDK_INT < FOREGROUND_TYPES) return
            context.startForegroundService(
                Intent(context, TurnService::class.java).putExtra(ABOUT, about),
            )
        }

        /** The turn is over, however it ended. */
        fun end(context: Context) {
            context.stopService(Intent(context, TurnService::class.java))
        }

        /**
         * Below API 34 there is no `specialUse`, and the alternatives are all
         * capped or wrong. The app runs there; a turn simply does not survive
         * being backgrounded, which is what it did before this existed.
         */
        private const val FOREGROUND_TYPES = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }
}
