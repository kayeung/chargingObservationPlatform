package com.chargelens.monitor

import com.chargelens.core.ChargeSample
import com.chargelens.data.SampleStore

enum class SessionEvent { NONE, STARTED, ENDED }

/**
 * Maintains the current charging session row so that a process death in the middle of a charge
 * still leaves a coherent record behind (the session is updated every 30 s and resumed on restart).
 */
class SessionTracker(private val store: SampleStore, private val deviceName: String) {

    var id: Long = 0L
        private set

    var startTs: Long = 0L
        private set

    var peakW: Float = 0f
        private set

    var energyWh: Double = 0.0
        private set

    var sampleCount: Int = 0
        private set

    var startSoc: Int = -1
        private set

    private var endSoc: Int = -1
    private var lastTs: Long = 0L
    private var lastPlug: Int = 0
    private var lastProtocol: String = ""
    private var lastConfidence: Int = 0

    fun restore() {
        val open = store.queryOpenSession() ?: return
        id = open.id
        startTs = open.startTs
        peakW = open.peakW
        energyWh = open.energyWh.toDouble()
        sampleCount = open.sampleCount
        startSoc = open.startSoc
        endSoc = open.endSoc
        lastPlug = open.plugged
        lastTs = 0L
        // Do not resume a session that has been idle for more than 6 hours.
        if (System.currentTimeMillis() - open.startTs > 6 * 3_600_000L) {
            lastPlug = 0
            id = 0L
        }
    }

    fun onSample(sample: ChargeSample): SessionEvent {
        var event = SessionEvent.NONE
        val plugged = sample.plugged

        if (plugged != 0 && lastPlug == 0) {
            begin(sample)
            event = SessionEvent.STARTED
        } else if (plugged == 0 && lastPlug != 0) {
            endSoc = if (sample.soc >= 0) sample.soc else endSoc
            close(sample.ts)
            event = SessionEvent.ENDED
        }
        lastPlug = plugged

        if (plugged != 0 && id != 0L && !sample.isGap) {
            sampleCount++
            if (sample.powerW > peakW) peakW = sample.powerW
            if (lastTs > 0L && sample.ts > lastTs) {
                // mW * ms -> Wh : 1 Wh = 3.6e9 mW*ms
                energyWh += sample.powerMw.toDouble() * (sample.ts - lastTs) / 3_600_000_000.0
            }
            if (sample.soc >= 0) endSoc = sample.soc
        }
        lastTs = sample.ts
        return event
    }

    /** Called when the sampling loop was suspended: do not integrate energy across the hole. */
    fun onGap() {
        lastTs = 0L
    }

    fun setProtocol(name: String, confidence: Int) {
        lastProtocol = name
        lastConfidence = confidence
    }

    /** Persists the running session; `endTs = 0` marks it as still open. */
    fun flush(now: Long, protocol: String, confidence: Int) {
        if (id == 0L) return
        setProtocol(protocol, confidence)
        store.updateSession(
            id = id,
            endTs = 0L,
            peakW = peakW,
            avgW = averageW(now),
            energyWh = energyWh.toFloat(),
            endSoc = endSoc,
            protocol = lastProtocol,
            confidence = lastConfidence,
            sampleCount = sampleCount
        )
    }

    fun close(ts: Long, protocol: String = lastProtocol, confidence: Int = lastConfidence) {
        if (id == 0L) return
        store.updateSession(
            id = id,
            endTs = ts,
            peakW = peakW,
            avgW = averageW(ts),
            energyWh = energyWh.toFloat(),
            endSoc = endSoc,
            protocol = protocol,
            confidence = confidence,
            sampleCount = sampleCount
        )
        id = 0L
        energyWh = 0.0
        peakW = 0f
        sampleCount = 0
        startSoc = -1
        lastProtocol = ""
        lastConfidence = 0
    }

    private fun averageW(now: Long): Float {
        val hours = (now - startTs).coerceAtLeast(0L) / 3_600_000.0
        return if (hours > 0.0005) (energyWh / hours).toFloat() else 0f
    }

    private fun begin(sample: ChargeSample) {
        startTs = sample.ts
        startSoc = sample.soc
        endSoc = sample.soc
        peakW = 0f
        energyWh = 0.0
        sampleCount = 0
        lastTs = sample.ts
        lastProtocol = ""
        lastConfidence = 0
        id = store.createSession(sample.ts, sample.plugged, sample.soc, deviceName)
    }
}
