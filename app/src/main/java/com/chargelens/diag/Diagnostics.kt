package com.chargelens.diag

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.File

/**
 * One-shot probe that enumerates **everything** a non-root app can see on this specific device:
 * every key of the battery sticky broadcast, every `BatteryManager` property id (including vendor
 * specific ones), long properties via reflection, USB port diagnostics, and a broad sysfs sweep.
 *
 * The report is written to `files/diag.txt` (pull with `adb shell run-as com.chargelens cat
 * files/diag.txt`) and mirrored to logcat under the `ChargeLensDiag` tag.
 */
object Diagnostics {

    const val TAG = "ChargeLensDiag"

    private val SYSFS_PATHS = listOf(
        "battery/voltage_now",
        "battery/current_now",
        "battery/current_avg",
        "battery/power_now",
        "battery/charge_counter",
        "battery/charge_type",
        "battery/step_charging_status",
        "battery/temp",
        "battery/health",
        "battery/status",
        "usb/voltage_now",
        "usb/current_now",
        "usb/charge_type",
        "usb/real_type",
        "usb/pd_active",
        "usb/typec_cc_orientation",
        "usb/online",
        "usb/present",
        "usb/input_current_limit",
        "wireless/power",
        "wireless/online",
        "wls/power",
        "wls/online",
        "wls_rx/power",
        "wls_rx/online",
        "vfcs-fchg/power",
        "vfcs-fchg/model_name",
        "vfcs-cp-0/power",
        "vfcs-cp-1/power",
        "mcu/power"
    )

    fun dumpAllAsync(context: Context, onDone: ((File) -> Unit)? = null) {
        val app = context.applicationContext
        Thread({
            val report = runCatching { dumpAll(app) }
                .getOrElse { "diagnostics failed:\n" + it.stackTraceToString() }
            val file = File(app.filesDir, "diag.txt")
            runCatching { file.writeText(report) }
            report.lineSequence().take(260).forEach { line ->
                runCatching { Log.i(TAG, line.take(900)) }
            }
            if (onDone != null) {
                Handler(Looper.getMainLooper()).post { onDone(file) }
            }
        }, "chargelens-diag").start()
    }

    fun dumpAll(context: Context): String {
        val sb = StringBuilder()
        fun line(text: String) {
            sb.append(text).append('\n')
        }

        line("=== ChargeLens diagnostics ===")
        line("time=${System.currentTimeMillis()}")
        line("device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        line("brand=${Build.BRAND} sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}")
        line("package=${context.packageName}")

        // ---------------------------------------------------------- sticky intent
        val sticky = runCatching {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        line("")
        line("=== ACTION_BATTERY_CHANGED sticky intent: ${if (sticky == null) "NULL" else "ok"} ===")
        val extras = sticky?.extras
        if (extras == null) {
            line("extras=NULL")
        } else {
            extras.keySet().sorted().forEach { key ->
                val value = rawExtra(extras, key)
                val type = value?.javaClass?.simpleName ?: "null"
                line("extra.$key = $value  [$type]")
            }
        }

        // ------------------------------------------------------- BatteryManager
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        line("")
        line("=== BatteryManager.getIntProperty(0..20) ===")
        if (manager == null) {
            line("BatteryManager=NULL")
        } else {
            for (id in 0..20) {
                val value = runCatching { manager.getIntProperty(id) }.getOrNull()
                if (value == null || value == Int.MIN_VALUE) {
                    line("intProperty[$id] = <unsupported>")
                } else {
                    line("intProperty[$id] = $value")
                }
            }
            val charging = runCatching { manager.isCharging }.getOrNull()
            line("isCharging = $charging")

            val longMethod = runCatching {
                BatteryManager::class.java.getMethod("getLongProperty", Int::class.javaPrimitiveType)
            }.getOrNull()
            if (longMethod == null) {
                line("getLongProperty = <unavailable on this API>")
            } else {
                for (id in 0..8) {
                    val value = runCatching { longMethod.invoke(manager, id) as? Long }.getOrNull()
                    line("longProperty[$id] = ${value ?: "<unsupported>"}")
                }
            }
        }

        // ------------------------------------------------------------- usb ports
        line("")
        line("=== USB ===")
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        line("deviceList.size = ${runCatching { usb?.deviceList?.size }.getOrNull()}")
        val ports = runCatching {
            UsbManager::class.java.getMethod("getPorts").invoke(usb)
        }
        ports.onSuccess { result ->
            val list = result as? List<*>
            line("getPorts() = ${list?.size ?: "null"} ports")
            list?.forEachIndexed { index, port ->
                if (port == null) return@forEachIndexed
                val cls = port.javaClass
                val name = runCatching { cls.getMethod("getPortName").invoke(port) }.getOrNull()
                val status = runCatching { cls.getMethod("getStatus").invoke(port) }.getOrNull()
                val warnings = runCatching { cls.getMethod("getComplianceWarnings").invoke(port) }.getOrNull()
                val supported = runCatching { cls.getMethod("getSupportedModes").invoke(port) }.getOrNull()
                line("port[$index] name=$name status=$status warnings=$warnings modes=$supported")
            }
        }.onFailure {
            line("getPorts() failed: ${it.javaClass.name}: ${it.message}")
        }

        // ------------------------------------------------------------- power mgr
        line("")
        line("=== power ===")
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        line("ignoringBatteryOptimizations = ${runCatching { power?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull()}")
        line("isPowerSaveMode = ${runCatching { power?.isPowerSaveMode }.getOrNull()}")
        line("isDeviceIdleMode = ${runCatching { power?.isDeviceIdleMode }.getOrNull()}")
        line("thermalStatus = ${runCatching { power?.currentThermalStatus }.getOrNull()}")

        // ---------------------------------------------------------------- sysfs
        line("")
        line("=== /sys/class/power_supply ===")
        val names = runCatching { File("/sys/class/power_supply").list()?.sorted() }.getOrNull()
        line("nodes = $names")
        SYSFS_PATHS.forEach { relative ->
            line("${probe("/sys/class/power_supply/$relative")}  <- $relative")
        }

        // -------------------------------------------------------------- database
        line("")
        line("=== database ===")
        runCatching {
            val store = com.chargelens.Graph.store
            line("samples = ${store.sampleCount()}")
            line("latestTs = ${store.latestSampleTs()}")
            store.latestSample()?.let { sample ->
                line(
                    "latest = ts=${sample.ts} mv=${sample.voltageMv} ua=${sample.currentUa} " +
                        "soc=${sample.soc} temp=${sample.tempC} plugged=${sample.plugged} " +
                        "v_speed=${sample.vendorSpeed} v_invalid=${sample.vendorInvalid} " +
                        "v_adapter=${sample.vendorAdapterPower} v_rwls=${sample.vendorReverseWireless} " +
                        "v_wotg=${sample.vendorWlsOtg} cycles=${sample.cycleCount}"
                )
            } ?: line("latest = <empty>")
        }.onFailure {
            line("db error: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ------------------------------------------------------- dumpsys attempt
        line("")
        line("=== exec /system/bin/dumpsys battery ===")
        val exec = runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/dumpsys", "battery"))
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            val code = process.waitFor()
            Triple(code, out, err)
        }
        exec.onSuccess { (code, out, err) ->
            line("exit=$code")
            line("stdout=${out.take(600).replace("\n", " | ")}")
            line("stderr=${err.take(300).replace("\n", " | ")}")
        }.onFailure {
            line("exec failed: ${it.javaClass.name}: ${it.message}")
        }

        line("")
        line("=== end ===")
        return sb.toString()
    }

    /**
     * Dumping an *unknown* extra requires the generic [Bundle.get]: the typed getters added in API 33
     * only work when the type is known in advance. The deprecation is therefore intentional and is
     * confined to this helper.
     */
    @Suppress("DEPRECATION")
    private fun rawExtra(extras: Bundle, key: String): Any? =
        runCatching { extras.get(key) }.getOrNull()

    private fun probe(path: String): String {
        val file = File(path)
        return try {
            val value = file.readText().trim()
            if (value.isEmpty()) "EMPTY" else "OK(${value.take(40)})"
        } catch (e: java.io.FileNotFoundException) {
            val message = e.message ?: ""
            if (message.contains("EACCES") || message.contains("Permission denied")) "DENIED" else "MISSING"
        } catch (t: Throwable) {
            "ERROR(${t.javaClass.simpleName})"
        }
    }
}
