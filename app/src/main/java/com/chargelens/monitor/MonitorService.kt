package com.chargelens.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.chargelens.Graph
import com.chargelens.MainActivity
import com.chargelens.R
import com.chargelens.core.BatterySampler
import com.chargelens.core.ChargeSample
import com.chargelens.core.CurrentCalibrator
import com.chargelens.core.Fmt
import com.chargelens.core.PlugType
import com.chargelens.data.Prefs
import com.chargelens.data.SampleStore
import com.chargelens.protocol.DeviceProfile
import com.chargelens.protocol.ProtocolAssessment
import com.chargelens.protocol.ProtocolEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Foreground sampling service.
 *
 * Why a foreground service and a partial wake lock: `ACTION_BATTERY_CHANGED` is throttled and is not
 * delivered when the device is suspended, but the interesting part of a fast charge (the ramp from
 * 18 W to 60-120 W) happens exactly during the first minutes, often with the screen off. A 1 Hz
 * timer plus a wake lock while plugged is the only reliable way to capture it without root.
 */
class MonitorService : Service() {

    private lateinit var sampler: BatterySampler
    private lateinit var prefs: Prefs
    private lateinit var store: SampleStore
    private lateinit var engine: ProtocolEngine
    private lateinit var device: DeviceProfile
    private lateinit var tracker: SessionTracker
    private lateinit var calibrator: CurrentCalibrator
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ArrayList<ChargeSample>(16)
    private val window = ArrayDeque<ChargeSample>(900)

    private var running = false
    private var previous: ChargeSample? = null
    private var assessment: ProtocolAssessment? = null
    private var lastLoggedProtocol: String? = null
    private var lastNotificationAt = 0L
    private var lastAssessAt = 0L
    private var lastFlushAt = 0L
    private var lastWakelockAt = 0L
    private var lastPruneAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                sampler.onBatteryChanged(intent)
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                tick(System.currentTimeMillis())
            } catch (t: Throwable) {
                // Never let one bad reading kill the sampling loop.
            }
            handler.postDelayed(this, sampleInterval())
        }
    }

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        sampler = BatterySampler(this)
        prefs = Graph.prefs
        store = Graph.store
        engine = Graph.engine
        device = Graph.device
        calibrator = CurrentCalibrator(prefs)
        tracker = SessionTracker(store, device.displayName)
        sampler.applyCalibration(prefs.settings.value.currentScale)

        handlerThread = HandlerThread("chargelens-sampler").apply { start() }
        handler = Handler(handlerThread.looper)

        createChannel()

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        try {
            ContextCompat.registerReceiver(this, batteryReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (t: Throwable) {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(batteryReceiver, filter)
        }

        tracker.restore()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.setMonitoringDesired(false)
            stopSelf()
            return START_NOT_STICKY
        }

        val started = try {
            startForeground(NOTIFICATION_ID, buildNotification(null))
            true
        } catch (t: Throwable) {
            false
        }
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }

        WatchdogReceiver.schedule(this)

        if (!running) {
            running = true
            handler.post(ticker)
            prefs.setMonitoringDesired(true)
            Graph.repo.setMonitoring(true)
            Graph.repo.setSession(tracker.startTs, tracker.energyWh.toFloat(), tracker.peakW, tracker.sampleCount)
        }
        ioScope.launch {
            val days = prefs.settings.value.retentionDays
            store.prune(System.currentTimeMillis() - days * 86_400_000L)
        }
        return START_STICKY
    }

    private fun tick(now: Long) {
        val raw = sampler.sample()

        // Screen-off / suspension gap: keep a marker instead of interpolating a fake straight line.
        val prev = previous
        if (prev != null && !prev.isGap && raw.ts - prev.ts > gapThreshold()) {
            tracker.onGap()
            val marker = raw.copy(voltageMv = 0, currentUa = 0, isGap = true, sessionId = tracker.id)
            Graph.repo.onSample(marker)
            persist(marker)
            val seconds = (raw.ts - prev.ts) / 1000
            ioScope.launch { store.insertEvent(raw.ts, tracker.id, "gap", "采样中断 $seconds 秒") }
        }

        val sessionBefore = tracker.id
        val event = tracker.onSample(raw)
        val sessionId = if (event == SessionEvent.ENDED) sessionBefore else tracker.id
        val sample = raw.copy(sessionId = sessionId)
        previous = sample

        when (event) {
            SessionEvent.STARTED -> {
                window.clear()
                assessment = null
                lastLoggedProtocol = null
                Graph.repo.resetWindow()
                Graph.repo.setSession(tracker.startTs, 0f, 0f, 0)
                ioScope.launch {
                    store.insertEvent(sample.ts, tracker.id, "plug", PlugType.label(sample.plugged))
                }
            }

            SessionEvent.ENDED -> {
                finishSession(sample)
            }

            SessionEvent.NONE -> Unit
        }

        if (sample.isPlugged && !sample.isGap) {
            window.addLast(sample)
            while (window.size > MAX_WINDOW) window.removeFirst()
        }

        Graph.repo.onSample(sample)
        persist(sample)

        calibrator.observe(sample)
        val learnedScale = prefs.settings.value.currentScale
        if (learnedScale != 1 && sampler.unitScale == 1) {
            sampler.applyCalibration(learnedScale)
        }

        if (sample.isPlugged) {
            updateWakelock(true, now)
        } else {
            updateWakelock(false, now)
        }

        if (now - lastAssessAt >= ASSESS_INTERVAL_MS && window.size >= 5) {
            lastAssessAt = now
            val result = engine.assess(window.toList(), device, prefs.settings.value.manualCharger)
            assessment = result
            tracker.setProtocol(result.top?.profile?.name ?: "未识别", result.confidence)
            Graph.repo.setAssessment(result)
            Graph.repo.setSession(
                tracker.startTs,
                tracker.energyWh.toFloat(),
                tracker.peakW,
                tracker.sampleCount
            )
            val id = result.top?.profile?.id
            if (id != null && id != lastLoggedProtocol && result.confidence >= 20) {
                lastLoggedProtocol = id
                ioScope.launch {
                    store.insertEvent(
                        result.updatedAt,
                        tracker.id,
                        "protocol",
                        "${result.top?.profile?.name} · ${result.confidence}%"
                    )
                }
            }
        }

        if (now - lastFlushAt >= SESSION_FLUSH_INTERVAL_MS) {
            lastFlushAt = now
            val top = assessment?.top?.profile?.name ?: "未识别"
            val confidence = assessment?.confidence ?: 0
            ioScope.launch { tracker.flush(now, top, confidence) }
            Graph.repo.setSession(
                tracker.startTs,
                tracker.energyWh.toFloat(),
                tracker.peakW,
                tracker.sampleCount
            )
        }

        if (now - lastNotificationAt >= NOTIFICATION_INTERVAL_MS) {
            lastNotificationAt = now
            notifyLive(sample, assessment)
        }

        if (now - lastPruneAt >= PRUNE_INTERVAL_MS) {
            lastPruneAt = now
            val days = prefs.settings.value.retentionDays
            ioScope.launch {
                store.prune(System.currentTimeMillis() - days * 86_400_000L)
            }
        }

        if (pending.size >= BATCH_SIZE) flushPending()
    }

    private fun finishSession(sample: ChargeSample) {
        val top = assessment?.top?.profile?.name ?: "未识别"
        val confidence = assessment?.confidence ?: 0
        ioScope.launch {
            store.insertEvent(sample.ts, sample.sessionId, "unplug", "本次充电结束")
            tracker.close(sample.ts, top, confidence)
            store.checkpoint()
        }
        Graph.repo.setSession(0L, 0f, 0f, 0)
        window.clear()
    }

    private fun persist(sample: ChargeSample) {
        pending.add(sample)
        if (pending.size >= BATCH_SIZE) flushPending()
    }

    private fun flushPending(sync: Boolean = false) {
        if (pending.isEmpty()) return
        val batch = ArrayList(pending)
        pending.clear()
        if (sync) {
            try {
                runBlocking { withContext(Dispatchers.IO) { store.insertSamples(batch) } }
            } catch (t: Throwable) {
                // ignore: nothing sensible to do while shutting down
            }
        } else {
            ioScope.launch { store.insertSamples(batch) }
        }
    }

    private fun sampleInterval(): Long {
        val base = prefs.settings.value.samplingIntervalMs.toLong()
        return if (previous?.isPlugged == true) base else maxOf(base, IDLE_INTERVAL_MS)
    }

    private fun gapThreshold(): Long = maxOf(sampleInterval() * 3, 5_000L)

    private fun updateWakelock(charging: Boolean, now: Long) {
        val manager = getSystemService(PowerManager::class.java) ?: return
        if (!charging || !prefs.settings.value.keepScreenOffAlive) {
            wakeLock?.let { if (it.isHeld) it.release() }
            lastWakelockAt = 0L
            return
        }
        val lock = wakeLock ?: manager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChargeLens::sampler")
            .also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        if (!lock.isHeld || now - lastWakelockAt >= WAKELOCK_RENEW_MS) {
            lastWakelockAt = now
            lock.acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(sample: ChargeSample?): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when {
            sample == null -> "正在启动充电监测"
            sample.isPlugged -> "充电中 · ${Fmt.watts(sample.powerW)} W"
            else -> "监测中 · 未充电"
        }
        val text = if (sample == null) {
            "等待电池数据"
        } else {
            val protocolName = assessment?.top?.profile?.name ?: "协议识别中"
            "${Fmt.volts(sample.voltageV)} V / ${Fmt.amps(sample.currentA)} A · " +
                "${PlugType.label(sample.plugged)} · $protocolName"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .addAction(0, "停止记录", stopIntent)
            .build()
    }

    private fun notifyLive(sample: ChargeSample, result: ProtocolAssessment?) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(sample))
        } catch (t: Throwable) {
            // POST_NOTIFICATIONS revoked: the service keeps running, only the card is hidden.
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (prefs.settings.value.monitoringDesired) WatchdogReceiver.schedule(this)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(batteryReceiver)
        } catch (t: Throwable) {
            // already unregistered
        }
        flushPending(sync = true)
        try {
            if (tracker.id != 0L) {
                val top = assessment?.top?.profile?.name ?: "未识别"
                tracker.close(System.currentTimeMillis(), top, assessment?.confidence ?: 0)
            }
        } catch (t: Throwable) {
            // database closed
        }
        runCatching { store.checkpoint() }
        wakeLock?.let { if (it.isHeld) it.release() }
        handlerThread.quitSafely()
        Graph.repo.setMonitoring(false)
        ioScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.chargelens.action.STOP"
        private const val CHANNEL_ID = "charge_monitor"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_WINDOW = 900
        private const val BATCH_SIZE = 8
        private const val ASSESS_INTERVAL_MS = 5_000L
        private const val SESSION_FLUSH_INTERVAL_MS = 30_000L
        private const val NOTIFICATION_INTERVAL_MS = 2_000L
        private const val PRUNE_INTERVAL_MS = 6 * 3_600_000L
        private const val IDLE_INTERVAL_MS = 5_000L
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60_000L
        private const val WAKELOCK_RENEW_MS = 4 * 60_000L
    }
}
