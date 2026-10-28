package com.chargelens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chargelens.core.ChargeSession
import com.chargelens.core.Fmt
import com.chargelens.core.PlugType
import com.chargelens.core.RangeStats
import com.chargelens.ui.components.ConfidenceBar
import com.chargelens.ui.components.KeyValueRow
import com.chargelens.ui.components.MetricTile
import com.chargelens.ui.components.PillSelector
import com.chargelens.ui.components.SectionCard
import com.chargelens.ui.components.StatusDot
import com.chargelens.ui.components.TrendChart
import com.chargelens.ui.theme.Amber
import com.chargelens.ui.theme.Sky
import com.chargelens.ui.theme.Teal

@Composable
fun HistoryScreen(vm: AppViewModel) {
    val state by vm.history.collectAsStateWithLifecycle()
    val ranges = HistoryRange.entries
    val metrics = Metric.entries

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "历史记录",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (state.loading) "正在加载…" else "共 ${state.stats.sampleCount} 个采样点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { vm.loadHistory(state.range, state.metric) }) { Text("刷新") }
            }
        }

        item {
            PillSelector(
                options = ranges.map { it.label },
                selected = ranges.indexOf(state.range).coerceAtLeast(0),
                onSelect = { index -> vm.loadHistory(ranges[index], state.metric) }
            )
        }

        item {
            SectionCard(
                title = "趋势",
                subtitle = "${state.range.label} · ${bucketLabel(state.bucketMs)}聚合"
            ) {
                PillSelector(
                    options = metrics.map { it.label },
                    selected = metrics.indexOf(state.metric).coerceAtLeast(0),
                    onSelect = { index -> vm.loadHistory(state.range, metrics[index]) }
                )
                Spacer(Modifier.height(10.dp))
                val accent = when (state.metric) {
                    Metric.POWER -> Teal
                    Metric.CURRENT -> Sky
                    Metric.VOLTAGE -> Amber
                }
                TrendChart(
                    points = state.points,
                    unit = state.metric.unit,
                    accent = accent,
                    height = 176.dp,
                    emptyText = "该时间段还没有记录"
                )
            }
        }

        item {
            StatsCard(state.stats)
        }

        item {
            Text(
                text = "充电会话",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (state.sessions.isEmpty()) {
            item {
                SectionCard {
                    Text(
                        text = "暂无会话记录。连接充电器并开启监测后，插拔之间的数据会自动归档为一次会话。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(items = state.sessions, key = { it.id }) { session ->
                SessionCard(session)
            }
        }
    }
}

private fun bucketLabel(ms: Long): String = when {
    ms < 60_000L -> "${(ms / 1_000L).coerceAtLeast(1L)} 秒"
    ms < 3_600_000L -> "${ms / 60_000L} 分钟"
    else -> "${ms / 3_600_000L} 小时"
}

@Composable
private fun StatsCard(stats: RangeStats) {
    SectionCard(title = "区间统计") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                label = "峰值",
                value = Fmt.watts(stats.peakW),
                unit = "W",
                accent = Teal,
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "均值",
                value = Fmt.watts(stats.avgW),
                unit = "W",
                accent = Sky,
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "充电量",
                value = Fmt.energy(stats.energyWh),
                unit = "Wh",
                accent = Amber,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        KeyValueRow("平均电压", "${Fmt.volts(stats.avgVoltageV)} V")
        KeyValueRow("平均电流", "${Fmt.amps(stats.avgCurrentA)} A")
        Text(
            text = "充电量为按平均功率与时间跨度估算值，精度低于会话统计。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SessionCard(session: ChargeSession) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${Fmt.dayLabel(session.startTs)} ${Fmt.clock(session.startTs)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${if (session.plugged and PlugType.WIRELESS != 0) "无线" else "有线"} · " +
                        "${Fmt.duration(session.durationMs)} · ${session.startSoc}% → ${session.endSoc}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Fmt.watts(session.peakW),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "峰值 W",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (session.confidence >= 45) Teal else Amber)
            Spacer(Modifier.width(6.dp))
            Text(
                text = session.protocol.ifBlank { "未识别" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${session.confidence}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        ConfidenceBar(fraction = session.confidence / 100f, color = Teal)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "充入 ${Fmt.energy(session.energyWh)} Wh",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "均值 ${Fmt.watts(session.avgW)} W · ${session.sampleCount} 点",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
