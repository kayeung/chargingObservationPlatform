package com.chargelens.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.chargelens.core.AggPoint
import com.chargelens.core.ChargeSample
import com.chargelens.core.ChargeSession
import com.chargelens.core.RangeStats
import java.io.BufferedWriter
import java.io.File

/**
 * Time-series store for charge samples.
 *
 * Plain [SQLiteOpenHelper] instead of Room on purpose: zero annotation processing, and the raw
 * insert path is what we need for a 1 Hz writer that must survive screen-off.
 */
class SampleStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_SAMPLES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                mv INTEGER NOT NULL DEFAULT 0,
                ua INTEGER NOT NULL DEFAULT 0,
                soc INTEGER NOT NULL DEFAULT -1,
                temp REAL NOT NULL DEFAULT 0,
                plugged INTEGER NOT NULL DEFAULT 0,
                status INTEGER NOT NULL DEFAULT 0,
                cc INTEGER NOT NULL DEFAULT -1,
                mxv INTEGER NOT NULL DEFAULT 0,
                mxi INTEGER NOT NULL DEFAULT 0,
                chgst INTEGER NOT NULL DEFAULT -1,
                v_speed INTEGER NOT NULL DEFAULT -1,
                v_invalid INTEGER NOT NULL DEFAULT -1,
                v_adapter INTEGER NOT NULL DEFAULT -1,
                v_rwls INTEGER NOT NULL DEFAULT -1,
                v_wotg INTEGER NOT NULL DEFAULT -1,
                cycles INTEGER NOT NULL DEFAULT -1,
                sid INTEGER NOT NULL DEFAULT 0,
                gap INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_samples_ts ON $TABLE_SAMPLES(ts)")
        db.execSQL("CREATE INDEX idx_samples_sid ON $TABLE_SAMPLES(sid)")

        db.execSQL(
            """
            CREATE TABLE $TABLE_SESSIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                start_ts INTEGER NOT NULL,
                end_ts INTEGER NOT NULL DEFAULT 0,
                plug INTEGER NOT NULL DEFAULT 0,
                peak_w REAL NOT NULL DEFAULT 0,
                avg_w REAL NOT NULL DEFAULT 0,
                energy_wh REAL NOT NULL DEFAULT 0,
                start_soc INTEGER NOT NULL DEFAULT -1,
                end_soc INTEGER NOT NULL DEFAULT -1,
                device TEXT NOT NULL DEFAULT '',
                protocol TEXT NOT NULL DEFAULT '',
                confidence INTEGER NOT NULL DEFAULT 0,
                sample_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_EVENTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                sid INTEGER NOT NULL DEFAULT 0,
                kind TEXT NOT NULL,
                detail TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_events_ts ON $TABLE_EVENTS(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 -> v2: vendor-specific battery extras (vivo charging_speed / adapter_power / ...)
        if (oldVersion < 2) {
            val columns = listOf(
                "v_speed INTEGER NOT NULL DEFAULT -1",
                "v_invalid INTEGER NOT NULL DEFAULT -1",
                "v_adapter INTEGER NOT NULL DEFAULT -1",
                "v_rwls INTEGER NOT NULL DEFAULT -1",
                "v_wotg INTEGER NOT NULL DEFAULT -1",
                "cycles INTEGER NOT NULL DEFAULT -1"
            )
            columns.forEach { column ->
                runCatching { db.execSQL("ALTER TABLE $TABLE_SAMPLES ADD COLUMN $column") }
            }
        }
    }

    // ---------------------------------------------------------------- samples

    fun insertSamples(samples: List<ChargeSample>): Int {
        if (samples.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            samples.forEach { s ->
                db.insertOrThrow(TABLE_SAMPLES, null, ContentValues().apply {
                    put("ts", s.ts)
                    put("mv", s.voltageMv)
                    put("ua", s.currentUa)
                    put("soc", s.soc)
                    put("temp", if (s.tempC.isNaN()) -999f else s.tempC)
                    put("plugged", s.plugged)
                    put("status", s.status)
                    put("cc", s.chargeCounterUah)
                    put("mxv", s.maxChargingVoltageUv)
                    put("mxi", s.maxChargingCurrentUa)
                    put("chgst", s.chargingStatus)
                    put("v_speed", s.vendorSpeed)
                    put("v_invalid", s.vendorInvalid)
                    put("v_adapter", s.vendorAdapterPower)
                    put("v_rwls", s.vendorReverseWireless)
                    put("v_wotg", s.vendorWlsOtg)
                    put("cycles", s.cycleCount)
                    put("sid", s.sessionId)
                    put("gap", if (s.isGap) 1 else 0)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return samples.size
    }

    fun latestSampleTs(): Long {
        readableDatabase.rawQuery("SELECT MAX(ts) FROM $TABLE_SAMPLES", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    /** Newest persisted sample, used by the diagnostic report to prove end-to-end storage. */
    fun latestSample(): ChargeSample? {
        readableDatabase.query(TABLE_SAMPLES, null, null, null, null, null, "ts DESC", "1").use { c ->
            if (!c.moveToFirst()) return null
            return ChargeSample(
                ts = c.getLong(c.getColumnIndexOrThrow("ts")),
                voltageMv = c.getInt(c.getColumnIndexOrThrow("mv")),
                currentUa = c.getInt(c.getColumnIndexOrThrow("ua")),
                soc = c.getInt(c.getColumnIndexOrThrow("soc")),
                tempC = c.getFloat(c.getColumnIndexOrThrow("temp")),
                plugged = c.getInt(c.getColumnIndexOrThrow("plugged")),
                status = c.getInt(c.getColumnIndexOrThrow("status")),
                chargeCounterUah = c.getInt(c.getColumnIndexOrThrow("cc")),
                maxChargingVoltageUv = c.getInt(c.getColumnIndexOrThrow("mxv")),
                maxChargingCurrentUa = c.getInt(c.getColumnIndexOrThrow("mxi")),
                chargingStatus = c.getInt(c.getColumnIndexOrThrow("chgst")),
                vendorSpeed = c.getInt(c.getColumnIndexOrThrow("v_speed")),
                vendorInvalid = c.getInt(c.getColumnIndexOrThrow("v_invalid")),
                vendorAdapterPower = c.getInt(c.getColumnIndexOrThrow("v_adapter")),
                vendorReverseWireless = c.getInt(c.getColumnIndexOrThrow("v_rwls")),
                vendorWlsOtg = c.getInt(c.getColumnIndexOrThrow("v_wotg")),
                cycleCount = c.getInt(c.getColumnIndexOrThrow("cycles")),
                sessionId = c.getLong(c.getColumnIndexOrThrow("sid")),
                isGap = c.getInt(c.getColumnIndexOrThrow("gap")) == 1
            )
        }
    }

    fun sampleCount(): Long {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_SAMPLES", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    fun aggregate(from: Long, to: Long, bucketMs: Long): List<AggPoint> {
        val bucket = bucketMs.coerceAtLeast(1000L)
        val sql = """
            SELECT (ts / $bucket) * $bucket AS b,
                   AVG(CASE WHEN gap = 0 THEN mv * ua / 1000000.0 ELSE 0 END) AS avg_mw,
                   MAX(CASE WHEN gap = 0 THEN mv * ua / 1000000.0 ELSE 0 END) AS max_mw,
                   AVG(mv) AS avg_mv,
                   AVG(ua) AS avg_ua,
                   AVG(soc) AS avg_soc,
                   COUNT(*) AS c
            FROM $TABLE_SAMPLES
            WHERE ts >= ? AND ts <= ?
            GROUP BY b
            ORDER BY b ASC
        """.trimIndent()
        val out = ArrayList<AggPoint>()
        readableDatabase.rawQuery(sql, arrayOf(from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) {
                out.add(
                    AggPoint(
                        ts = c.getLong(0),
                        avgMw = c.getDouble(1).toFloat(),
                        maxMw = c.getDouble(2).toFloat(),
                        voltageV = (c.getDouble(3) / 1000.0).toFloat(),
                        currentA = (c.getDouble(4) / 1_000_000.0).toFloat(),
                        soc = c.getDouble(5).toFloat(),
                        count = c.getInt(6)
                    )
                )
            }
        }
        return out
    }

    fun stats(from: Long, to: Long): RangeStats {
        val sql = """
            SELECT MAX(mv * ua / 1000000.0) AS peak,
                   AVG(mv * ua / 1000000.0) AS avg,
                   MIN(mv * ua / 1000000.0) AS low,
                   AVG(mv) AS avg_mv,
                   AVG(ua) AS avg_ua,
                   COUNT(*) AS c,
                   MIN(ts) AS first_ts,
                   MAX(ts) AS last_ts
            FROM $TABLE_SAMPLES
            WHERE ts >= ? AND ts <= ? AND gap = 0
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(from.toString(), to.toString())).use { c ->
            if (!c.moveToFirst() || c.getInt(5) == 0) return RangeStats()
            val avgW = c.getDouble(1).toFloat() / 1000f
            // Integrate over the span that actually has data, not over the queried window.
            val spanHours = ((c.getLong(7) - c.getLong(6)).coerceAtLeast(0L) / 3_600_000.0)
            return RangeStats(
                peakW = c.getDouble(0).toFloat() / 1000f,
                avgW = avgW,
                minW = c.getDouble(2).toFloat() / 1000f,
                energyWh = (avgW * spanHours).toFloat(),
                avgVoltageV = (c.getDouble(3) / 1000.0).toFloat(),
                avgCurrentA = (c.getDouble(4) / 1_000_000.0).toFloat(),
                sampleCount = c.getInt(5),
                firstTs = c.getLong(6),
                lastTs = c.getLong(7)
            )
        }
    }

    /** Keeps the -wal file from growing during long screen-off sessions. */
    fun checkpoint() {
        runCatching {
            writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        }
    }

    fun prune(olderThan: Long): Int {
        val db = writableDatabase
        val samples = db.delete(TABLE_SAMPLES, "ts < ?", arrayOf(olderThan.toString()))
        db.delete(TABLE_EVENTS, "ts < ?", arrayOf(olderThan.toString()))
        return samples
    }

    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_SAMPLES, null, null)
            db.delete(TABLE_EVENTS, null, null)
            db.delete(TABLE_SESSIONS, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // --------------------------------------------------------------- sessions

    fun createSession(startTs: Long, plugged: Int, startSoc: Int, device: String): Long {
        val values = ContentValues().apply {
            put("start_ts", startTs)
            put("plug", plugged)
            put("start_soc", startSoc)
            put("end_soc", startSoc)
            put("device", device)
        }
        return writableDatabase.insert(TABLE_SESSIONS, null, values)
    }

    fun updateSession(
        id: Long,
        endTs: Long,
        peakW: Float,
        avgW: Float,
        energyWh: Float,
        endSoc: Int,
        protocol: String,
        confidence: Int,
        sampleCount: Int
    ) {
        if (id <= 0L) return
        writableDatabase.update(
            TABLE_SESSIONS,
            ContentValues().apply {
                put("end_ts", endTs)
                put("peak_w", peakW)
                put("avg_w", avgW)
                put("energy_wh", energyWh)
                put("end_soc", if (endSoc < 0) 0 else endSoc)
                put("protocol", protocol)
                put("confidence", confidence)
                put("sample_count", sampleCount)
            },
            "id = ?",
            arrayOf(id.toString())
        )
    }

    fun querySessions(limit: Int = 40): List<ChargeSession> {
        val out = ArrayList<ChargeSession>()
        readableDatabase.query(
            TABLE_SESSIONS, null, null, null, null, null, "start_ts DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ChargeSession(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        startTs = c.getLong(c.getColumnIndexOrThrow("start_ts")),
                        endTs = c.getLong(c.getColumnIndexOrThrow("end_ts")),
                        plugged = c.getInt(c.getColumnIndexOrThrow("plug")),
                        peakW = c.getFloat(c.getColumnIndexOrThrow("peak_w")),
                        avgW = c.getFloat(c.getColumnIndexOrThrow("avg_w")),
                        energyWh = c.getFloat(c.getColumnIndexOrThrow("energy_wh")),
                        startSoc = c.getInt(c.getColumnIndexOrThrow("start_soc")),
                        endSoc = c.getInt(c.getColumnIndexOrThrow("end_soc")),
                        device = c.getString(c.getColumnIndexOrThrow("device")) ?: "",
                        protocol = c.getString(c.getColumnIndexOrThrow("protocol")) ?: "",
                        confidence = c.getInt(c.getColumnIndexOrThrow("confidence")),
                        sampleCount = c.getInt(c.getColumnIndexOrThrow("sample_count"))
                    )
                )
            }
        }
        return out
    }

    fun queryOpenSession(): ChargeSession? {
        readableDatabase.query(
            TABLE_SESSIONS, null, "end_ts = 0", null, null, null, "start_ts DESC", "1"
        ).use { c ->
            if (!c.moveToFirst()) return null
            return ChargeSession(
                id = c.getLong(c.getColumnIndexOrThrow("id")),
                startTs = c.getLong(c.getColumnIndexOrThrow("start_ts")),
                endTs = 0L,
                plugged = c.getInt(c.getColumnIndexOrThrow("plug")),
                peakW = c.getFloat(c.getColumnIndexOrThrow("peak_w")),
                avgW = c.getFloat(c.getColumnIndexOrThrow("avg_w")),
                energyWh = c.getFloat(c.getColumnIndexOrThrow("energy_wh")),
                startSoc = c.getInt(c.getColumnIndexOrThrow("start_soc")),
                endSoc = c.getInt(c.getColumnIndexOrThrow("end_soc")),
                device = c.getString(c.getColumnIndexOrThrow("device")) ?: "",
                protocol = c.getString(c.getColumnIndexOrThrow("protocol")) ?: "",
                confidence = c.getInt(c.getColumnIndexOrThrow("confidence")),
                sampleCount = c.getInt(c.getColumnIndexOrThrow("sample_count"))
            )
        }
    }

    // ----------------------------------------------------------------- events

    fun insertEvent(ts: Long, sessionId: Long, kind: String, detail: String) {
        writableDatabase.insert(TABLE_EVENTS, null, ContentValues().apply {
            put("ts", ts)
            put("sid", sessionId)
            put("kind", kind)
            put("detail", detail)
        })
    }

    fun recentEvents(limit: Int = 30): List<Triple<Long, String, String>> {
        val out = ArrayList<Triple<Long, String, String>>()
        readableDatabase.query(
            TABLE_EVENTS, arrayOf("ts", "kind", "detail"), null, null, null, null, "ts DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Triple(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: ""))
            }
        }
        return out
    }

    // ------------------------------------------------------------------- csv

    fun writeCsv(target: File, from: Long, to: Long): Int {
        target.parentFile?.mkdirs()
        var rows = 0
        target.bufferedWriter(Charsets.UTF_8).use { writer: BufferedWriter ->
            writer.write("\uFEFF")
            writer.write(
                "timestamp,iso_time,voltage_mV,current_uA,power_mW,soc,temp_C,plugged,status," +
                    "session_id,gap,vendor_speed,vendor_invalid,adapter_power,reverse_wireless,wls_otg,cycle_count\n"
            )
            readableDatabase.query(
                TABLE_SAMPLES,
                arrayOf("ts", "mv", "ua", "soc", "temp", "plugged", "status", "sid", "gap",
                    "v_speed", "v_invalid", "v_adapter", "v_rwls", "v_wotg", "cycles"),
                "ts >= ? AND ts <= ?",
                arrayOf(from.toString(), to.toString()),
                null, null, "ts ASC"
            ).use { c: Cursor ->
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                while (c.moveToNext()) {
                    val ts = c.getLong(0)
                    val mv = c.getInt(1)
                    val ua = c.getInt(2)
                    writer.write(stamp.format(java.util.Date(ts)))
                    writer.write(",")
                    writer.write(ts.toString())
                    writer.write(",")
                    writer.write(mv.toString())
                    writer.write(",")
                    writer.write(ua.toString())
                    writer.write(",")
                    writer.write((mv.toLong() * ua / 1_000_000L).toString())
                    writer.write(",")
                    writer.write(c.getInt(3).toString())
                    writer.write(",")
                    writer.write(c.getFloat(4).toString())
                    writer.write(",")
                    writer.write(c.getInt(5).toString())
                    writer.write(",")
                    writer.write(c.getInt(6).toString())
                    writer.write(",")
                    writer.write(c.getLong(7).toString())
                    writer.write(",")
                    writer.write(c.getInt(8).toString())
                    for (index in 9..14) {
                        writer.write(",")
                        writer.write(c.getInt(index).toString())
                    }
                    writer.newLine()
                    rows++
                }
            }
        }
        return rows
    }

    companion object {
        private const val DB_NAME = "chargelens.db"
        private const val DB_VERSION = 2
        private const val TABLE_SAMPLES = "samples"
        private const val TABLE_SESSIONS = "sessions"
        private const val TABLE_EVENTS = "events"
    }
}
