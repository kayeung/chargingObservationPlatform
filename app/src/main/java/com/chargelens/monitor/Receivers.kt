package com.chargelens.monitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chargelens.Graph

/** Restarts monitoring after a reboot, but only if the user left it enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context)
        if (Graph.prefs.settings.value.monitoringDesired) {
            MonitorController.start(context)
        }
    }
}

/**
 * Cheap watchdog: if the OEM power manager freezes the service while the screen is off, the alarm
 * brings it back. It also runs after the task is removed from recents.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context)
        if (Graph.prefs.settings.value.monitoringDesired) {
            MonitorController.start(context)
        }
    }

    companion object {
        private const val REQUEST_CODE = 4101
        private const val INTERVAL_MS = 15 * 60_000L

        fun schedule(context: Context) {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = pendingIntent(context)
            try {
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + INTERVAL_MS,
                    pending
                )
            } catch (t: Throwable) {
                // Exact alarms restricted; the sticky service is the primary mechanism anyway.
            }
        }

        fun cancel(context: Context) {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            manager.cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
