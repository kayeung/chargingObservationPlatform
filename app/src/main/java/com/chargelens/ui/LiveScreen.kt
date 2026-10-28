package com.chargelens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chargelens.core.ChargeSample
import com.chargelens.core.Fmt
import com.chargelens.core.PlugType
import com.chargelens.data.LiveState
import com.chargelens.data.Settings
import com.chargelens.protocol.ProtocolAssessment
import com.chargelens.ui.components.ConfidenceBar
import com.chargelens.ui.components.KeyValueRow
import com.chargelens.ui.components.MetricTile
import com.chargelens.ui.components.PillSelector
import com.chargelens.ui.components.PowerDial
import com.chargelens.ui.components.SectionCard
import com.chargelens.ui.components.StatusDot
import com.chargelens.ui.components.TrendChart
import com.chargelens.ui.theme.Amber
import com.chargelens.ui.theme.Coral
import com.chargelens.ui.theme.Sky
import com.chargelens.ui.theme.Teal
import com.chargelens.ui.theme.Violet

@Composable
fun LiveScreen(
    state: LiveState,
    settings: Settings,
    onToggleMonitoring: (Boolean) -> Unit,
    onExport: () -> Unit,
    onOpenProtocol: () -> Unit
) {
    val last = state.last

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Header(state.monitoring, onToggleMonitoring)
        }
        if (last == null || last.isGap) {
            item {
                SectionCard(title = "等待数据") {
                    Text(
                        text = "开启监测并连接充电器后，这里会实时显示电池侧的功率、电流、电压，" +
                            "并根据功率曲线推断充电协议。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { onToggleMonitoring(true) }) { Text("开始监测") }
                }
            }
        } else {
            item {
                PowerCard(last, state, settings)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile(
                        label = "电压",
                        value = Fmt.volts(last.voltageV),
                        unit = "V",
                        accent = Amber,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "电流",
                        value = Fmt.amps(last.currentA),
                        unit = "A",
                        accent = Sky,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "温度",
                        value = Fmt.temp(last.tempC),
                        unit = "°C",
                        accent = if (last.tempC >= 42f) Coral else Violet,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                SocRow(last)
            }
            item {
                RealtimeChartCard(state)
            }
            item {
                ProtocolSummaryCard(state.assessment, onOpenProtocol)
            }
            item {
                SessionCard(state, onExport)
            }
        }
    }
}

@Composable
private fun Header(monitoring: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "充电观测台",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (monitoring) Teal else Coral)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (monitoring) "前台服务运行中 · 1 Hz 采样" else "监测已停止",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = monitoring, onCheckedChange = onToggle)
    }
}

@Composable
private fun PowerCard(sample: ChargeSample, state: LiveState, settings: Settings) {
    val maxW = remember(state.sessionPeakW, sample.powerW) {
        maxOf(30f, maxOf(state.sessionPeakW, sample.powerW) * 1.15f).coerceAtMost(260f)
    }
    val status = when {
        sample.isFull -> "已充满"
        sample.isCharging -> "充电中"
        sample.isPlugged -> "已连接"
        else -> "放电中"
    }
    val efficiency = settings.efficiency.coerceIn(0.6f, 1f)
    val estimated = sample.powerW / efficiency

    SectionCard {
        PowerDial(
            powerW = sample.powerW,
            maxW = maxW,
            statusText = status,
            subText = "${PlugType.label(sample.plugged)} · 电池侧实测算得"
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            InfoChip("估算输入", "${Fmt.watts(estimated)} W", Teal, Modifier.weight(1f))
            InfoChip("折算效率", "${(efficiency * 100).toInt()}%", Amber, Modifier.weight(1f))
            InfoChip(
                "采样间隔",
                "${settings.samplingIntervalMs.coerceAtLeast(500) / 1000} 秒",
                Sky,
                Modifier.weight(1f)
            )
        }
        LimitHints(sample)
    }
}

@Composable
private fun InfoChip(label: String, value: String, accent: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.09f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            ),
            color = accent
        )
    }
}

/**
 * Explains the common reasons a high-power phone shows a low battery-side wattage, so the reading is
 * not mistaken for a broken charger or a bug in the app.
 */
@Composable
private fun LimitHints(sample: ChargeSample) {
    val hints = buildList {
        if (sample.vendorInvalid == 1) {
            add("系统标记充电器未通过校验（invalid_charger=1），可能已回退到公有协议")
        }
        if (sample.plugged and PlugType.USB != 0 && sample.powerW < 4f) {
            add("接在 USB 数据口时供电受限，此时的功率不代表充电器上限")
        }
        if (sample.soc >= 95 && sample.powerW < 3f) {
            add("电量接近满，功率已被电池接受能力钳制")
        }
        if (sample.isWireless) {
            add("无线充电链路效率较低，电池侧功率会明显低于适配器标称")
        }
    }.take(2)
    if (hints.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    hints.forEach { hint ->
        Text(
            text = "· $hint",
            style = MaterialTheme.typography.labelSmall,
            color = if (hint.contains("未通过校验")) Coral else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SocRow(sample: ChargeSample) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "电量",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (sample.soc >= 0) "${sample.soc}%" else "--",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (sample.status == 5) "已充满" else "${Fmt.watts(sample.powerW)} W 输入电池",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        ConfidenceBar(
            fraction = (sample.soc.coerceAtLeast(0) / 100f),
            color = Teal
        )
    }
}

@Composable
private fun RealtimeChartCard(state: LiveState) {
    val metrics = listOf(Metric.POWER, Metric.CURRENT, Metric.VOLTAGE)
    val accents = listOf(Teal, Sky, Amber)
    var selected by remember { mutableIntStateOf(0) }
    val metric = metrics[selected]
    val samples = state.window.filter { !it.isGap && it.voltageMv > 0 }
    val points = samples.map { TrendPoint(it.ts, metric.of(it)) }

    SectionCard(
        title = "实时曲线",
        subtitle = if (samples.isEmpty()) "等待采样" else "最近 ${samples.size} 个采样点 · 可拖动查看数值"
    ) {
        PillSelector(
            options = metrics.map { it.label },
            selected = selected,
            onSelect = { selected = it }
        )
        Spacer(Modifier.height(10.dp))
        TrendChart(
            points = points,
            unit = metric.unit,
            accent = accents[selected],
            height = 168.dp,
            emptyText = "持续采样 2 秒后显示曲线"
        )
    }
}

@Composable
private fun ProtocolSummaryCard(assessment: ProtocolAssessment?, onOpen: () -> Unit) {
    val data = assessment
    val top = data?.top
    SectionCard(
        title = "协议推断",
        subtitle = when {
            data == null || top == null -> "样本不足"
            else -> "${data.transport.label} · 可信度 ${top.confidence}% · 推断结果"
        },
        trailing = {
            TextButton(onClick = onOpen) { Text("详情") }
        }
    ) {
        if (top == null) {
            Text(
                text = "连续记录 10 秒以上、且在低电量阶段充电时，协议特征最明显。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }
        Text(
            text = top.profile.name,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${top.profile.family.label} · ${top.profile.hint.ifBlank { "基于功率指纹匹配" }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        ConfidenceBar(fraction = top.confidence / 100f, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        data.evidence.take(2).forEach { EvidenceLine(it) }
    }
}

@Composable
fun EvidenceLine(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SessionCard(state: LiveState, onExport: () -> Unit) {
    val startedAt = state.sessionStart
    SectionCard(title = "本次充电", subtitle = if (startedAt > 0L) "开始于 ${Fmt.full(startedAt)}" else "未在充电") {
        KeyValueRow("已记录", Fmt.duration(if (startedAt > 0L) System.currentTimeMillis() - startedAt else 0L))
        KeyValueRow("峰值功率", "${Fmt.watts(state.sessionPeakW)} W")
        KeyValueRow("充入能量", "${Fmt.energy(state.sessionEnergyWh)} Wh")
        KeyValueRow("采样点", "${state.sampleCount}")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onExport, modifier = Modifier.weight(1f)) { Text("导出 CSV") }
            TextButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                Text("分享数据", fontSize = 14.sp)
            }
        }
    }
}
