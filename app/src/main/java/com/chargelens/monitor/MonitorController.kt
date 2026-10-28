package com.chargelens.monitor

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.chargelens.Graph

object MonitorController {

    fun start(context: Context) {
        Graph.init(context)
        val intent = Intent(context, MonitorService::class.java)
        try {
            ContextCompat.startForegroundService(context, intent)
            Graph.prefs.setMonitoringDesired(true)
        } catch (t: Throwable) {
            // Background start restrictions (Android 12+) or OEM freeze: the UI will surface it.
            Graph.prefs.setMonitoringDesired(false)
        }
    }

    fun stop(context: Context) {
        Graph.init(context)
        Graph.prefs.setMonitoringDesired(false)
        val intent = Intent(context, MonitorService::class.java)
        try {
            context.startService(intent.setAction(MonitorService.ACTION_STOP))
        } catch (t: Throwable) {
            context.stopService(intent)
        }
    }

    fun isRunning(): Boolean = Graph.repo.state.value.monitoring
}
