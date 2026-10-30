package com.chargelens

import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.chargelens.core.BatteryExtras
import com.chargelens.diag.Diagnostics
import com.chargelens.monitor.MonitorController
import com.chargelens.ui.ChargeLabApp
import com.chargelens.ui.theme.ChargeLensTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(this)
        enableEdgeToEdge()
        setContent {
            ChargeLensTheme {
                ChargeLabApp()
            }
        }
        if (BuildConfig.DEBUG) {
            Diagnostics.dumpAllAsync(applicationContext)
        }
        startMonitoringIfCharging()
    }

    private fun startMonitoringIfCharging() {
        if (!Graph.prefs.settings.value.autoStartOnLaunch) return
        if (Graph.repo.state.value.monitoring) return
        // `BatteryManager.isCharging` is unreliable on some OEM builds: the vivo test device reports
        // false while the sticky broadcast says plugged=AC and status=charging. Trust the broadcast.
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (BatteryExtras.from(sticky).plugged != 0) {
            MonitorController.start(this)
        }
    }
}
