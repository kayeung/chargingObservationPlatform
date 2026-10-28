package com.chargelens.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.chargelens.BuildConfig
import com.chargelens.Graph
import com.chargelens.data.Settings as AppSettings
import com.chargelens.diag.Diagnostics
import com.chargelens.ui.components.ConfidenceBar
import com.chargelens.ui.components.PillSelector
import com.chargelens.ui.components.SectionCard
import com.chargelens.ui.theme.Amber
import com.chargelens.ui.theme.Coral
import com.chargelens.ui.theme.Teal

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onInterval: (Int) -> Unit,
    onRetention: (Int) -> Unit,
    onAutoStart: (Boolean) -> Unit,
    onKeepAlive: (Boolean) -> Unit,
    onEfficiency: (Float) -> Unit,
    onManualCharger: (String) -> Unit,
    onPrivacyMode: (Boolean) -> Unit,
    onExport: () -> Unit,
    onClearData: () -> Unit
) {
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    var batteryIgnored by remember { mutableStateOf(isIgnoringOptimizations(context)) }
    var notificationsGranted by remember { mutableStateOf(hasNotificationPermission(context)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "采样、保活与识别辅助参数",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SectionCard(title = "采样") {
                Text(
                    text = "充电中采样间隔",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                val intervals = listOf(1000, 2000, 5000)
                PillSelector(
                    options = intervals.map { "${it / 1000} 秒" },
                    selected = intervals.indexOf(settings.samplingIntervalMs).coerceAtLeast(0),
                    onSelect = { onInterval(intervals[it]) }
                )
                Spacer(Modifier.height(14.dp))
                SwitchRow(
                    title = "熄屏保活",
                    subtitle = "充电时持有唤醒锁，确保功率爬升阶段不丢点",
                    checked = settings.keepScreenOffAlive,
                    onCheckedChange = onKeepAlive
                )
                SwitchRow(
                    title = "打开应用自动开始记录",
                    subtitle = "仅在检测到正在充电时启动前台服务",
                    checked = settings.autoStartOnLaunch,
                    onCheckedChange = onAutoStart
                )
            }
        }

        item {
            SectionCard(
                title = "系统限制",
                subtitle = "国内 ROM 的省电策略比 AOSP 激进得多",
                trailing = {
                    TextButton(onClick = {
                        batteryIgnored = isIgnoringOptimizations(context)
                        notificationsGranted = hasNotificationPermission(context)
                    }) { Text("刷新") }
                }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "电池优化白名单",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (batteryIgnored) "已忽略电池优化" else "未加入白名单，熄屏后可能被冻结",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (batteryIgnored) Teal else Coral
                        )
                    }
                    TextButton(onClick = {
                        requestIgnoreOptimizations(context)
                        batteryIgnored = isIgnoringOptimizations(context)
                    }) { Text("去设置") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "通知权限",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (notificationsGranted) "已授权，前台服务通知正常显示" else "未授权，通知卡片不可见（服务仍可运行）",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (notificationsGranted) Teal else Amber
                        )
                    }
                    TextButton(onClick = {
                        openNotificationSettings(context)
                        notificationsGranted = hasNotificationPermission(context)
                    }) { Text("去设置") }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "额外建议：在 vivo / 小米 / OPPO 的“自启动”“后台高耗电”“电池优化”中允许本应用，" +
                        "否则系统可能在熄屏数分钟后直接冻结进程，导致曲线出现断点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SectionCard(title = "数据") {
                Text(
                    text = "原始采样保留时长",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                val days = listOf(7, 30, 90)
                PillSelector(
                    options = days.map { "$it 天" },
                    selected = days.indexOf(settings.retentionDays).coerceAtLeast(0),
                    onSelect = { onRetention(days[it]) }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "充电时每秒 1 条，1 小时约 3600 条；非充电状态每 5 秒一条。正常使用 30 天通常不超过 30 万条。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    title = "隐私模式",
                    subtitle = "隐藏机型与电池循环次数，便于截图分享",
                    checked = settings.privacyMode,
                    onCheckedChange = onPrivacyMode
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onExport, modifier = Modifier.weight(1f)) { Text("导出 CSV") }
                    TextButton(onClick = { confirmClear = true }, modifier = Modifier.weight(1f)) {
                        Text("清空数据", color = Coral)
                    }
                }
            }
        }

        item {
            SectionCard(title = "识别辅助", subtitle = "用于提高协议推断的先验权重") {
                Text(
                    text = "输入功率折算效率 ${(settings.efficiency * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val eff = settings.efficiency
                var localEff by remember(eff) { mutableFloatStateOf(eff) }
                Slider(
                    value = localEff,
                    onValueChange = { localEff = it },
                    valueRange = 0.75f..1f,
                    onValueChangeFinished = { onEfficiency(localEff) }
                )
                Text(
                    text = "电池侧功率 ÷ 效率 = 估算的适配器输出功率。双电芯电荷泵机型通常 0.90–0.94，" +
                        "单电芯降压直充 0.85–0.90。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                var charger by remember { mutableStateOf(settings.manualCharger) }
                OutlinedTextField(
                    value = charger,
                    onValueChange = {
                        charger = it.take(40)
                        onManualCharger(charger)
                    },
                    label = { Text("充电器型号（可选）") },
                    placeholder = { Text("例如：小米 120W 原装") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "标注充电器后，同品牌协议档位会获得更高权重；这是目前最可靠的提升准确度方式。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SectionCard(title = "关于") {
                Text(
                    text = "充电观测台 v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${Graph.device.displayName} · Android ${Build.VERSION.SDK_INT}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "所有数据来自 Android 公开 API 与电池管理系统上报值，不读取内核私有节点，" +
                        "不索取 Root，不上传任何信息。功率为电池侧计算值，与适配器标称功率存在效率差。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConfidenceBar(fraction = 0.5f, color = Amber, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "推断类结论仅供参考",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "诊断报告会枚举本机所有可读的电池字段与内核节点",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        Diagnostics.dumpAllAsync(context) { file ->
                            Toast.makeText(context, "已写入 ${file.name}", Toast.LENGTH_LONG).show()
                        }
                    }) { Text("生成") }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空历史数据") },
            text = { Text("将删除全部采样点、会话与事件记录，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearData()
                }) { Text("确认清空", color = Coral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun isIgnoringOptimizations(context: Context): Boolean {
    val manager = context.getSystemService(PowerManager::class.java) ?: return false
    return manager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun requestIgnoreOptimizations(context: Context) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    )
    val opened = runCatching { context.startActivity(direct) }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
    }
}

private fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
}
