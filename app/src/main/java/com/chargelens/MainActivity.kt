package com.chargelens

import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        val battery = getSystemService(BatteryManager::class.java) ?: return
        if (battery.isCharging) {
            MonitorController.start(this)
        }
    }
}
