package com.chargelens.ui

import com.chargelens.core.AggPoint
import com.chargelens.core.ChargeSample

data class TrendPoint(val ts: Long, val value: Float)

enum class Metric(val label: String, val unit: String) {
    POWER("功率", "W"),
    CURRENT("电流", "A"),
    VOLTAGE("电压", "V");

    fun of(sample: ChargeSample): Float = when (this) {
        POWER -> sample.powerW
        CURRENT -> sample.currentA
        VOLTAGE -> sample.voltageV
    }

    fun of(point: AggPoint): Float = when (this) {
        POWER -> point.avgMw / 1000f
        CURRENT -> point.currentA
        VOLTAGE -> point.voltageV
    }
}

enum class HistoryRange(val label: String, val durationMs: Long, val bucketMs: Long) {
    HOUR("1 小时", 3_600_000L, 60_000L),
    DAY("24 小时", 86_400_000L, 5 * 60_000L),
    WEEK("7 天", 7 * 86_400_000L, 30 * 60_000L),
    MONTH("30 天", 30 * 86_400_000L, 2 * 3_600_000L)
}
