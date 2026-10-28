package com.chargelens.data

import com.chargelens.core.ChargeSample
import com.chargelens.protocol.Capability
import com.chargelens.protocol.ProtocolAssessment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveState(
    val last: ChargeSample? = null,
    val window: List<ChargeSample> = emptyList(),
    val assessment: ProtocolAssessment? = null,
    val capability: Capability? = null,
    val monitoring: Boolean = false,
    val sessionStart: Long = 0L,
    val sessionEnergyWh: Float = 0f,
    val sessionPeakW: Float = 0f,
    val sampleCount: Int = 0
)

/** In-memory ring buffer shared between the sampling service and the Compose UI. */
class LiveRepository {

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val window = ArrayDeque<ChargeSample>(MAX_WINDOW)

    fun onSample(sample: ChargeSample) {
        if (sample.isGap) {
            // Keep one marker so the chart shows a break instead of a straight line.
            window.addLast(sample)
            trim()
            _state.value = _state.value.copy(last = sample, window = window.toList())
            return
        }
        window.addLast(sample)
        trim()
        _state.value = _state.value.copy(
            last = sample,
            window = window.toList(),
            sessionStart = _state.value.sessionStart,
            sampleCount = _state.value.sampleCount + 1
        )
    }

    fun resetWindow() {
        window.clear()
        _state.value = _state.value.copy(window = emptyList(), sampleCount = 0)
    }

    fun setMonitoring(value: Boolean) {
        _state.value = _state.value.copy(monitoring = value)
    }

    fun setAssessment(value: ProtocolAssessment) {
        _state.value = _state.value.copy(assessment = value)
    }

    fun setCapability(value: Capability) {
        _state.value = _state.value.copy(capability = value)
    }

    fun setSession(startTs: Long, energyWh: Float, peakW: Float, sampleCount: Int) {
        _state.value = _state.value.copy(
            sessionStart = startTs,
            sessionEnergyWh = energyWh,
            sessionPeakW = peakW,
            sampleCount = sampleCount
        )
    }

    private fun trim() {
        while (window.size > MAX_WINDOW) window.removeFirst()
    }

    companion object {
        private const val MAX_WINDOW = 360
    }
}
