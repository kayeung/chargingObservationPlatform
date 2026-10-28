package com.chargelens.core

import com.chargelens.data.Prefs

/**
 * Learns whether `BATTERY_PROPERTY_CURRENT_NOW` is really micro-amperes on this device.
 *
 * Android documents uA, but several OEM kernels report mA. We compare the integrated current to the
 * coulomb counter (`charge_counter`, uAh) over a few minutes: if the integration over-counts by
 * ~1000x, the property is mA and a permanent scale factor is stored.
 */
class CurrentCalibrator(private val prefs: Prefs) {

    private var anchor: ChargeSample? = null
    private var integratedUaSeconds: Double = 0.0
    private var lastSample: ChargeSample? = null

    var lastRatio: Double = 0.0
        private set

    var lastCheckedAt: Long = 0L
        private set

    fun observe(sample: ChargeSample) {
        val prev = lastSample
        lastSample = sample

        if (prefs.settings.value.currentScale != 1) return
        if (!sample.isCharging || sample.chargeCounterUah < 0) {
            reset(sample)
            return
        }
        if (prev == null || prev.chargeCounterUah < 0 || !prev.isCharging) {
            reset(sample)
            return
        }
        val start = anchor ?: run { reset(sample); return }

        val dtSeconds = ((sample.ts - prev.ts).coerceIn(0L, 15_000L)) / 1000.0
        integratedUaSeconds += prev.currentUa * dtSeconds

        val elapsed = sample.ts - start.ts
        if (elapsed < WINDOW_MS) return

        val deltaUah = (sample.chargeCounterUah - start.chargeCounterUah).toDouble()
        if (deltaUah <= 0.0) {
            reset(sample)
            return
        }
        val expectedUah = integratedUaSeconds / 3600.0
        val ratio = expectedUah / deltaUah
        lastRatio = ratio
        lastCheckedAt = sample.ts
        if (ratio in 300.0..4000.0) {
            prefs.setCurrentScale(1000)
        }
        reset(sample)
    }

    private fun reset(sample: ChargeSample) {
        anchor = sample
        integratedUaSeconds = 0.0
    }

    companion object {
        private const val WINDOW_MS = 180_000L
    }
}
