package com.chargelens.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Everything Android hands to a normal (non-root) app through the battery sticky broadcast.
 *
 * The extra keys `max_charging_voltage` / `max_charging_current` / `charging_status` are written by
 * AOSP's BatteryService since Android 14 (some OEMs never fill them, some fill them with 0).
 * They are read as raw strings on purpose so the app also builds against older SDKs.
 */
data class BatteryExtras(
    val voltageMv: Int = 0,
    val tempC: Float = Float.NaN,
    val level: Int = -1,
    val scale: Int = 100,
    val plugged: Int = 0,
    val status: Int = BatteryManager.BATTERY_STATUS_UNKNOWN,
    val chargeCounterUah: Int = -1,
    val maxChargingVoltageUv: Int = 0,
    val maxChargingCurrentUa: Int = 0,
    val chargingStatus: Int = -1,
    val health: Int = 0,
    val technology: String = "",
    /** vivo: "charging_speed" — OEM charging gear/state enum. */
    val vendorSpeed: Int = -1,
    /** vivo: "invalid_charger" — 1 means the charger failed OEM validation. */
    val vendorInvalidCharger: Int = -1,
    /** vivo: "adapter_power" — adapter-reported power, unit not documented. */
    val vendorAdapterPower: Int = -1,
    /** vivo: "reverse_wireless_chg_state" — reverse wireless charging state. */
    val vendorReverseWireless: Int = -1,
    /** vivo: "wls_otg_status" — wireless OTG state. */
    val vendorWlsOtg: Int = -1,
    /** Android 14+: "android.os.extra.CYCLE_COUNT". */
    val cycleCount: Int = -1
) {
    val soc: Int
        get() = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1

    companion object {
        fun from(intent: Intent?): BatteryExtras {
            if (intent == null) return BatteryExtras()
            val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            return BatteryExtras(
                voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
                tempC = if (rawTemp == Int.MIN_VALUE) Float.NaN else rawTemp / 10f,
                level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100),
                plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
                status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
                chargeCounterUah = intent.getIntExtra("charge_counter", -1),
                maxChargingVoltageUv = intent.getIntExtra("max_charging_voltage", 0),
                maxChargingCurrentUa = intent.getIntExtra("max_charging_current", 0),
                chargingStatus = intent.getIntExtra("charging_status", -1),
                health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, 0),
                technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "",
                vendorSpeed = intent.getIntExtra("charging_speed", -1),
                vendorInvalidCharger = intent.getIntExtra("invalid_charger", -1),
                vendorAdapterPower = intent.getIntExtra("adapter_power", -1),
                vendorReverseWireless = intent.getIntExtra("reverse_wireless_chg_state", -1),
                vendorWlsOtg = intent.getIntExtra("wls_otg_status", -1),
                cycleCount = intent.getIntExtra("android.os.extra.CYCLE_COUNT", -1)
            )
        }
    }
}

/**
 * Reads the fuel gauge.
 *
 * Unit quirks handled here:
 *  - `BATTERY_PROPERTY_CURRENT_NOW` is documented as micro-amperes but a number of OEMs report
 *    milli-amperes; the scale factor is learned at runtime by [CurrentCalibrator].
 *  - Some devices report the charging current as negative; the sign is normalised when the
 *    framework already says the battery is charging.
 */
class BatterySampler(private val context: Context) {

    private val batteryManager: BatteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    private var cachedExtras: BatteryExtras? = null

    var unitScale: Int = 1
        private set

    var signFlipped: Boolean = false
        private set

    var currentSupported: Boolean = true
        private set

    var lastRawCurrentUa: Int = 0
        private set

    fun applyCalibration(scale: Int) {
        unitScale = if (scale <= 0) 1 else scale
    }

    fun onBatteryChanged(intent: Intent) {
        cachedExtras = BatteryExtras.from(intent)
    }

    fun extras(): BatteryExtras {
        cachedExtras?.let { return it }
        val sticky = try {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (t: Throwable) {
            null
        }
        val parsed = BatteryExtras.from(sticky)
        cachedExtras = parsed
        return parsed
    }

    fun sample(): ChargeSample {
        val extras = extras()
        val raw = try {
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        } catch (t: Throwable) {
            Int.MIN_VALUE
        }
        if (raw == Int.MIN_VALUE) currentSupported = false
        lastRawCurrentUa = raw

        var microAmps: Long = if (raw == Int.MIN_VALUE) 0L else raw.toLong()
        if (unitScale != 1) {
            microAmps *= unitScale
            microAmps = microAmps.coerceIn(-2_000_000_000L, 2_000_000_000L)
        }

        val chargingNow = extras.plugged != 0 &&
            (extras.status == BatteryManager.BATTERY_STATUS_CHARGING ||
                extras.status == BatteryManager.BATTERY_STATUS_FULL)
        if (chargingNow && microAmps < 0L) {
            microAmps = -microAmps
            signFlipped = true
        } else {
            signFlipped = false
        }

        return ChargeSample(
            ts = System.currentTimeMillis(),
            voltageMv = extras.voltageMv.coerceIn(0, 60_000),
            currentUa = microAmps.coerceIn(-2_000_000_000L, 2_000_000_000L).toInt(),
            soc = extras.soc,
            tempC = extras.tempC,
            plugged = extras.plugged,
            status = extras.status,
            chargeCounterUah = extras.chargeCounterUah,
            maxChargingVoltageUv = extras.maxChargingVoltageUv,
            maxChargingCurrentUa = extras.maxChargingCurrentUa,
            chargingStatus = extras.chargingStatus,
            vendorSpeed = extras.vendorSpeed,
            vendorInvalid = extras.vendorInvalidCharger,
            vendorAdapterPower = extras.vendorAdapterPower,
            vendorReverseWireless = extras.vendorReverseWireless,
            vendorWlsOtg = extras.vendorWlsOtg,
            cycleCount = extras.cycleCount
        )
    }
}
