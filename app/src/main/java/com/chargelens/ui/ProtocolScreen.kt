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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chargelens.data.LiveState
import com.chargelens.protocol.Capability
import com.chargelens.protocol.ProtocolAssessment
import com.chargelens.protocol.SignalStatus
import com.chargelens.ui.components.ConfidenceBar
import com.chargelens.ui.components.SectionCard
import com.chargelens.ui.components.StatusDot
import com.chargelens.ui.theme.Amber
import com.chargelens.ui.theme.Coral
import com.chargelens.ui.theme.Sky
import com.chargelens.ui.theme.Teal

@Composable
fun ProtocolScreen(
    state: LiveState,
    privacyMode: Boolean,
    onRefreshCapability: () -> Unit
) {
    val assessment = state.assessment
    val capability = state.capability

    LaunchedEffect(Unit) {
        if (capability == null) onRefreshCapability()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "协议识别",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "免 Root 条件下只能做概率性推断",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onRefreshCapability) { Text("重新自检") }
            }
        }

        item { ResultCard(assessment, privacyMode) }
        item { CandidatesCard(assessment) }
        item { EvidenceCard(assessment) }
        item { VendorFieldsCard(assessment, privacyMode) }
        item { CapabilityCard(capability, privacyMode, onRefreshCapability) }
        item { BrandLimitsCard() }
    }
}

@Composable
private fun ResultCard(assessment: ProtocolAssessment?, privacyMode: Boolean) {
    val data = assessment
    val top = data?.top
    SectionCard(title = "识别结论") {
        if (data == null || top == null) {
            Text(
                text = "还没有足够的样本。建议在电量低于 40% 时插上充电器，保持记录 1–2 分钟，" +
                    "这段区间最能体现握手与功率爬升特征。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }
        Text(
            text = top.profile.name,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${top.profile.family.label} · ${data.transport.label} · " +
                if (privacyMode) "已脱敏设备" else data.device,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "可信度",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${top.confidence}%",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(6.dp))
        ConfidenceBar(fraction = top.confidence / 100f, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        Text(
            text = data.disclaimer,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CandidatesCard(assessment: ProtocolAssessment?) {
    val candidates = assessment?.candidates.orEmpty()
    if (candidates.isEmpty()) return
    SectionCard(title = "候选排序", subtitle = "同一批次数据下的相对可能性") {
        candidates.forEachIndexed { index, candidate ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(16.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = candidate.profile.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (index == 0) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    ConfidenceBar(
                        fraction = candidate.confidence / 100f,
                        color = when (index) {
                            0 -> Teal
                            1 -> Sky
                            else -> Amber
                        }
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${candidate.confidence}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (index != candidates.lastIndex) Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun EvidenceCard(assessment: ProtocolAssessment?) {
    val evidence = assessment?.evidence.orEmpty()
    if (evidence.isEmpty()) return
    SectionCard(title = "判定依据") {
        evidence.forEach { EvidenceLine(it) }
    }
}

@Composable
private fun VendorFieldsCard(assessment: ProtocolAssessment?, privacyMode: Boolean) {
    val signals = assessment?.vendorSignals.orEmpty()
        .filter { !(privacyMode && it.key == "cycle_count") }
    if (signals.isEmpty()) return
    SectionCard(
        title = "厂商扩展字段",
        subtitle = "非公开 API，读取自电池广播里的 OEM 自定义键"
    ) {
        signals.forEach { signal ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = signal.key,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = signal.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = signal.value,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "取值含义由厂商定义（例如 vivo 的 charging_speed 可能是档位枚举），应用只如实展示原始值，" +
                "不据此单独下结论；需要结合低电量段的功率曲线一起标定。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CapabilityCard(capability: Capability?, privacyMode: Boolean, onRefresh: () -> Unit) {
    SectionCard(
        title = "本机可观测性",
        subtitle = if (capability == null) "正在检测…" else buildString {
            if (!privacyMode) append(capability.model).append(" · ")
            append("Android ").append(capability.release)
            append(" · ").append(capability.availableCount).append("/").append(capability.totalCount).append(" 项可用")
        },
        trailing = {
            TextButton(onClick = onRefresh) { Text("检测") }
        }
    ) {
        if (capability == null) {
            Text(
                text = "正在探测系统公开字段与内核节点…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }
        Text(
            text = capability.verdict,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(10.dp))
        capability.signals.forEach { signal ->
            val color = when (signal.status) {
                SignalStatus.AVAILABLE -> Teal
                SignalStatus.DEGRADED -> Amber
                SignalStatus.BLOCKED -> Coral
                SignalStatus.UNSUPPORTED -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(Modifier.padding(vertical = 5.dp)) {
                Box(Modifier.padding(top = 6.dp)) { StatusDot(color, 6.dp) }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = signal.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = signal.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BrandLimitsCard() {
    SectionCard(title = "各品牌私有协议的限制") {
        BrandLimit("小米 / 红米", "HyperCharge、MI Turbo Charge 由充电器与 PMIC 私下协商（部分叠加 PD PPS 扩展），应用层不可读。只能按 33 / 67 / 90 / 120 / 200 W 档位与电荷泵电流形态推断。")
        BrandLimit("vivo / iQOO", "FlashCharge 由独立闪充 IC（实测节点 vfcs-fchg、vfcs-cp-0/1）控制，节点存在但 SELinux 拒绝普通应用访问。免 Root 只能按功率档位推断。")
        BrandLimit("魅族", "mCharge / Super mCharge 通过 D+/D- 私有电平或 PD PPS 协商，系统未暴露握手结果，中高电量段与其他 PD 机型难以区分。")
        BrandLimit("华为 / 荣耀", "SCP 使用 D+/D- 私有握手实现 4.5V/5A 级低压大电流，FCP 为 9V 档；Framework 不提供任何握手状态，只能靠功率与电压平台推断。")
        Spacer(Modifier.height(8.dp))
        Text(
            text = "共同限制：功率进入恒压段后会被电池接受能力钳制，此时 67W 与 120W 机型曲线几乎重合，" +
                "因此本应用给出的是“协议族 + 档位区间”，而不是确定的协议版本。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BrandLimit(title: String, body: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
