package com.chargelens.core

/** Values mirror [android.os.BatteryManager.EXTRA_PLUGGED]. */
object PlugType {
    const val NONE = 0
    const val AC = 1
    const val USB = 2
    const val WIRELESS = 4
    const val DOCK = 8

    fun label(plugged: Int): String = when {
        plugged and WIRELESS != 0 -> "无线充电"
        plugged and AC != 0 -> "有线充电"
        plugged and USB != 0 -> "USB 供电"
        plugged and DOCK != 0 -> "底座充电"
        else -> "未充电"
    }

    fun isWired(plugged: Int): Boolean = plugged and AC != 0 || plugged and USB != 0 || plugged and DOCK != 0
}

/**
 * One observation of the battery fuel gauge.
 *
 * [voltageMv] and [currentUa] are the *battery-side* values exposed by Android, they are NOT the
 * charger output (VBUS) values. See docs/feasibility.md for the consequences.
 */
data class ChargeSample(
    val ts: Long,
    val voltageMv: Int,
    val currentUa: Int,
    val soc: Int,
    val tempC: Float,
    val plugged: Int,
    val status: Int,
    val chargeCounterUah: Int,
    val maxChargingVoltageUv: Int,
    val maxChargingCurrentUa: Int,
    val chargingStatus: Int,
    /** OEM-specific battery extras (vivo exposes charging_speed / adapter_power / invalid_charger). */
    val vendorSpeed: Int = -1,
    val vendorInvalid: Int = -1,
    val vendorAdapterPower: Int = -1,
    val vendorReverseWireless: Int = -1,
    val vendorWlsOtg: Int = -1,
    val cycleCount: Int = -1,
    val sessionId: Long = 0L,
    val isGap: Boolean = false
) {
    /** mV * uA = nW, divided by 1e6 gives mW. */
    val powerMw: Long = if (isGap) 0L else voltageMv.toLong() * currentUa.toLong() / 1_000_000L
    val powerW: Float get() = powerMw / 1000f
    val voltageV: Float get() = voltageMv / 1000f
    val currentA: Float get() = currentUa / 1_000_000f

    val isWireless: Boolean get() = plugged and PlugType.WIRELESS != 0
    val isPlugged: Boolean get() = plugged != 0
    val isCharging: Boolean
        get() = isPlugged && (status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL)
    val isFull: Boolean get() = status == android.os.BatteryManager.BATTERY_STATUS_FULL
    val hasTemperature: Boolean get() = !tempC.isNaN() && tempC > -40f && tempC < 120f
}

/** One charging session, delimited by plug / unplug events. */
data class ChargeSession(
    val id: Long,
    val startTs: Long,
    val endTs: Long,
    val plugged: Int,
    val peakW: Float,
    val avgW: Float,
    val energyWh: Float,
    val startSoc: Int,
    val endSoc: Int,
    val device: String,
    val protocol: String,
    val confidence: Int,
    val sampleCount: Int
) {
    val isOpen: Boolean get() = endTs == 0L

    /** Open sessions keep counting up to "now" so the UI never shows 0 分钟 for an active charge. */
    val durationMs: Long
        get() {
            val end = if (endTs == 0L) System.currentTimeMillis() else endTs
            return (end - startTs).coerceAtLeast(0L)
        }
    val transport: String get() = if (plugged and PlugType.WIRELESS != 0) "无线" else "有线"
}

/** A down-sampled bucket returned by the history queries. */
data class AggPoint(
    val ts: Long,
    val avgMw: Float,
    val maxMw: Float,
    val voltageV: Float,
    val currentA: Float,
    val soc: Float,
    val count: Int
)

data class RangeStats(
    val peakW: Float = 0f,
    val avgW: Float = 0f,
    val minW: Float = 0f,
    val energyWh: Float = 0f,
    val avgVoltageV: Float = 0f,
    val avgCurrentA: Float = 0f,
    val sampleCount: Int = 0,
    val firstTs: Long = 0L,
    val lastTs: Long = 0L
) {
    val spanMs: Long get() = (lastTs - firstTs).coerceAtLeast(0L)
}
