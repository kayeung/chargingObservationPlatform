package com.chargelens.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private data class TabItem(val label: String, val icon: ImageVector)

@Composable
fun ChargeLabApp(vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    val live by vm.live.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val exportUri by vm.exportUri.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var tab by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(exportUri) {
        exportUri?.let { uri ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(Intent.createChooser(send, "导出充电数据")) }
            vm.consumeExport()
        }
    }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }

    LaunchedEffect(tab) {
        if (tab == 1) vm.loadHistory()
        if (tab == 2 && live.capability == null) vm.refreshCapability()
    }

    val tabs = listOf(
        TabItem("实时", Icons.Filled.Home),
        TabItem("记录", Icons.Filled.DateRange),
        TabItem("协议", Icons.Filled.Info),
        TabItem("设置", Icons.Filled.Settings)
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (tab) {
                0 -> LiveScreen(
                    state = live,
                    settings = settings,
                    onToggleMonitoring = { enabled ->
                        if (enabled) vm.startMonitoring(context) else vm.stopMonitoring(context)
                    },
                    onExport = { vm.exportCsv(context) },
                    onOpenProtocol = { tab = 2 }
                )

                1 -> HistoryScreen(vm)
                2 -> ProtocolScreen(live, settings.privacyMode) { vm.refreshCapability() }
                else -> SettingsScreen(
                    settings = settings,
                    onInterval = vm::setInterval,
                    onRetention = vm::setRetention,
                    onAutoStart = vm::setAutoStart,
                    onKeepAlive = vm::setKeepAlive,
                    onEfficiency = vm::setEfficiency,
                    onManualCharger = vm::setManualCharger,
                    onPrivacyMode = vm::setPrivacyMode,
                    onExport = { vm.exportCsv(context) },
                    onClearData = vm::clearData
                )
            }
        }
    }
}
