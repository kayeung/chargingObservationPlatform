package com.chargelens.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Small formatting helpers, kept locale-stable (always '.' as decimal separator). */
object Fmt {

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val minuteFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
    private val fullFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    fun watts(w: Float): String = when {
        w >= 100f -> String.format(Locale.US, "%.0f", w)
        w >= 10f -> String.format(Locale.US, "%.1f", w)
        else -> String.format(Locale.US, "%.2f", w)
    }

    fun volts(v: Float): String = String.format(Locale.US, "%.2f", v)

    fun amps(a: Float): String = when {
        a >= 10f -> String.format(Locale.US, "%.1f", a)
        a >= 1f -> String.format(Locale.US, "%.2f", a)
        else -> String.format(Locale.US, "%.3f", a)
    }

    fun temp(c: Float): String = if (c.isNaN()) "--" else String.format(Locale.US, "%.1f", c)

    fun percent(p: Int): String = "$p%"

    fun time(ts: Long): String =
        timeFmt.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()))

    fun clock(ts: Long): String =
        minuteFmt.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()))

    fun day(ts: Long): String =
        dayFmt.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()))

    fun full(ts: Long): String =
        fullFmt.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()))

    fun dayLabel(ts: Long): String {
        val date = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        return when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> dayFmt.format(date)
        }
    }

    fun duration(ms: Long): String {
        if (ms <= 0L) return "0 分钟"
        val totalMinutes = ms / 60_000L
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h > 0 -> "${h} 小时 ${m} 分"
            totalMinutes > 0 -> "${m} 分钟"
            else -> "${ms / 1000} 秒"
        }
    }

    fun energy(wh: Float): String = when {
        wh >= 100f -> String.format(Locale.US, "%.0f", wh)
        wh >= 10f -> String.format(Locale.US, "%.1f", wh)
        else -> String.format(Locale.US, "%.2f", wh)
    }
}
