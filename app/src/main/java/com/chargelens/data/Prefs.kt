package com.chargelens.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val samplingIntervalMs: Int = 1000,
    val retentionDays: Int = 30,
    val autoStartOnLaunch: Boolean = true,
    val monitoringDesired: Boolean = false,
    val currentScale: Int = 1,
    val efficiency: Float = 0.92f,
    val manualCharger: String = "",
    val keepScreenOffAlive: Boolean = true,
    /** Hides device model and battery health values, e.g. for screenshots or shared reports. */
    val privacyMode: Boolean = false
)

class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("chargelens", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private fun load(): Settings = Settings(
        samplingIntervalMs = sp.getInt("interval", 1000).coerceIn(500, 10_000),
        retentionDays = sp.getInt("retention", 30).coerceIn(1, 365),
        autoStartOnLaunch = sp.getBoolean("auto_start", true),
        monitoringDesired = sp.getBoolean("monitoring_desired", false),
        currentScale = sp.getInt("current_scale", 1),
        efficiency = sp.getFloat("efficiency", 0.92f).coerceIn(0.6f, 1f),
        manualCharger = sp.getString("manual_charger", "") ?: "",
        keepScreenOffAlive = sp.getBoolean("keep_alive", true),
        privacyMode = sp.getBoolean("privacy_mode", false)
    )

    private fun update(block: (Settings) -> Settings) {
        val next = block(_settings.value)
        _settings.value = next
        sp.edit()
            .putInt("interval", next.samplingIntervalMs)
            .putInt("retention", next.retentionDays)
            .putBoolean("auto_start", next.autoStartOnLaunch)
            .putBoolean("monitoring_desired", next.monitoringDesired)
            .putInt("current_scale", next.currentScale)
            .putFloat("efficiency", next.efficiency)
            .putString("manual_charger", next.manualCharger)
            .putBoolean("keep_alive", next.keepScreenOffAlive)
            .putBoolean("privacy_mode", next.privacyMode)
            .apply()
    }

    fun setInterval(ms: Int) = update { it.copy(samplingIntervalMs = ms.coerceIn(500, 10_000)) }
    fun setRetention(days: Int) = update { it.copy(retentionDays = days.coerceIn(1, 365)) }
    fun setAutoStart(value: Boolean) = update { it.copy(autoStartOnLaunch = value) }
    fun setMonitoringDesired(value: Boolean) = update { it.copy(monitoringDesired = value) }
    fun setCurrentScale(scale: Int) = update { it.copy(currentScale = if (scale <= 0) 1 else scale) }
    fun setEfficiency(value: Float) = update { it.copy(efficiency = value.coerceIn(0.6f, 1f)) }
    fun setManualCharger(value: String) = update { it.copy(manualCharger = value.take(60)) }
    fun setKeepAlive(value: Boolean) = update { it.copy(keepScreenOffAlive = value) }
    fun setPrivacyMode(value: Boolean) = update { it.copy(privacyMode = value) }
}
