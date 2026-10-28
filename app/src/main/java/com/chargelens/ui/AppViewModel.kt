package com.chargelens.ui

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chargelens.BuildConfig
import com.chargelens.Graph
import com.chargelens.core.ChargeSession
import com.chargelens.core.RangeStats
import com.chargelens.data.LiveState
import com.chargelens.data.Settings
import com.chargelens.monitor.MonitorController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryState(
    val range: HistoryRange = HistoryRange.DAY,
    val metric: Metric = Metric.POWER,
    val loading: Boolean = false,
    val points: List<TrendPoint> = emptyList(),
    val stats: RangeStats = RangeStats(),
    val sessions: List<ChargeSession> = emptyList(),
    val bucketMs: Long = 60_000L
)

class AppViewModel : ViewModel() {

    val live: StateFlow<LiveState> = Graph.repo.state
    val settings: StateFlow<Settings> = Graph.prefs.settings

    private val _history = MutableStateFlow(HistoryState())
    val history: StateFlow<HistoryState> = _history.asStateFlow()

    private val _exportUri = MutableStateFlow<Uri?>(null)
    val exportUri: StateFlow<Uri?> = _exportUri.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        loadHistory(HistoryRange.DAY, Metric.POWER)
    }

    fun loadHistory(range: HistoryRange = _history.value.range, metric: Metric = _history.value.metric) {
        _history.update { it.copy(range = range, metric = metric, loading = true) }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val from = now - range.durationMs
            val loaded = withContext(Dispatchers.IO) {
                val stats = Graph.store.stats(from, now)
                // A short session would collapse into a single fixed bucket and render as "no data";
                // derive the bucket from the span that actually has samples.
                val bucket = if (stats.sampleCount > 1 && stats.spanMs > 1_000L) {
                    (stats.spanMs / 240L).coerceIn(1_000L, range.bucketMs)
                } else {
                    range.bucketMs
                }
                val points = Graph.store.aggregate(from, now, bucket).map {
                    TrendPoint(it.ts, metric.of(it))
                }
                LoadedHistory(points, stats, Graph.store.querySessions(40), bucket)
            }
            _history.update {
                it.copy(
                    loading = false,
                    points = loaded.points,
                    stats = loaded.stats,
                    sessions = loaded.sessions,
                    bucketMs = loaded.bucket
                )
            }
        }
    }

    fun refreshCapability() {
        viewModelScope.launch {
            val capability = withContext(Dispatchers.IO) { Graph.capabilityProbe.probe() }
            Graph.repo.setCapability(capability)
        }
    }

    fun startMonitoring(context: Context) = MonitorController.start(context)

    fun stopMonitoring(context: Context) = MonitorController.stop(context)

    fun exportCsv(context: Context) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val base = appContext.getExternalFilesDir(null) ?: appContext.filesDir
                    val dir = File(base, "export").apply { mkdirs() }
                    val name = "chargelens_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
                    val file = File(dir, name)
                    Graph.store.writeCsv(file, 0L, System.currentTimeMillis())
                    FileProvider.getUriForFile(appContext, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
                }.getOrNull()
            }
            if (uri != null) {
                _exportUri.value = uri
            } else {
                _message.value = "导出失败，请检查存储空间"
            }
        }
    }

    fun consumeExport() {
        _exportUri.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun clearData() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Graph.store.clearAll() }
            _message.value = "历史数据已清空"
            loadHistory()
        }
    }

    fun setInterval(ms: Int) = Graph.prefs.setInterval(ms)
    fun setRetention(days: Int) = Graph.prefs.setRetention(days)
    fun setAutoStart(value: Boolean) = Graph.prefs.setAutoStart(value)
    fun setKeepAlive(value: Boolean) = Graph.prefs.setKeepAlive(value)
    fun setPrivacyMode(value: Boolean) = Graph.prefs.setPrivacyMode(value)
    fun setEfficiency(value: Float) = Graph.prefs.setEfficiency(value)
    fun setManualCharger(value: String) = Graph.prefs.setManualCharger(value)
}

private data class LoadedHistory(
    val points: List<TrendPoint>,
    val stats: RangeStats,
    val sessions: List<ChargeSession>,
    val bucket: Long
)
