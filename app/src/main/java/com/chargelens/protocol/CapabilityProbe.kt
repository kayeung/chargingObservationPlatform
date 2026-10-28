package com.chargelens.protocol

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import com.chargelens.core.BatteryExtras
import com.chargelens.core.Fmt
import java.io.File
import java.io.FileNotFoundException

enum class SignalStatus { AVAILABLE, DEGRADED, BLOCKED, UNSUPPORTED }

data class SignalProbe(
    val name: String,
    val status: SignalStatus,
    val detail: String
)

data class Capability(
    val model: String,
    val brand: String,
    val sdk: Int,
    val release: String,
    val signals: List<SignalProbe>,
    val availableCount: Int,
    val totalCount: Int,
    val checkedAt: Long,
    val verdict: String
)

/**
 * Answers one question honestly: on *this* phone, which of the signals a charging analyser would
 * want are actually reachable by a normal, non-root app?
 *
 * The sysfs probes are the interesting part: they demonstrate in-app that the kernel exposes
 * VBUS / charge_type, but SELinux denies access to `untrusted_app` (and often even to `shell`).
 */
class CapabilityProbe(private val context: Context) {

    fun probe(): Capability {
        val signals = ArrayList<SignalProbe>()
        val sticky = runCatching {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val extras = BatteryExtras.from(sticky)

        signals.add(
            when {
                extras.voltageMv in 2_000..12_000 ->
                    SignalProbe("电池电压（EXTRA_VOLTAGE）", SignalStatus.AVAILABLE, "${Fmt.volts(extras.voltageMv / 1000f)} V")

                extras.voltageMv == 0 ->
                    SignalProbe("电池电压（EXTRA_VOLTAGE）", SignalStatus.UNSUPPORTED, "系统未上报")

                else ->
                    SignalProbe("电池电压（EXTRA_VOLTAGE）", SignalStatus.DEGRADED, "${extras.voltageMv} mV，偏离电芯范围")
            }
        )

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val currentNow = runCatching {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE
        }.getOrDefault(Int.MIN_VALUE)
        signals.add(
            when {
                currentNow == Int.MIN_VALUE ->
                    SignalProbe("电池电流（CURRENT_NOW）", SignalStatus.UNSUPPORTED, "该机型未实现此属性")

                currentNow == 0 ->
                    SignalProbe("电池电流（CURRENT_NOW）", SignalStatus.DEGRADED, "当前读数为 0（满电涓流或该机型未实现）")

                else ->
                    SignalProbe(
                        "电池电流（CURRENT_NOW）",
                        SignalStatus.AVAILABLE,
                        "${Fmt.amps(currentNow / 1_000_000f)} A（原始值 $currentNow）"
                    )
            }
        )

        signals.add(
            if (extras.chargeCounterUah >= 0)
                SignalProbe("库仑计（charge_counter）", SignalStatus.AVAILABLE, "${extras.chargeCounterUah} µAh")
            else
                SignalProbe("库仑计（charge_counter）", SignalStatus.UNSUPPORTED, "未上报，无法用于电流单位校准")
        )

        val hasMaxV = sticky?.hasExtra("max_charging_voltage") == true
        val maxV = extras.maxChargingVoltageUv
        val maxI = extras.maxChargingCurrentUa
        signals.add(
            when {
                hasMaxV && (maxV > 0 || maxI > 0) -> SignalProbe(
                    "协商上限电压/电流（Android 14+）",
                    SignalStatus.AVAILABLE,
                    "${Fmt.volts(maxV / 1_000_000f)} V / ${Fmt.amps(maxI / 1_000_000f)} A"
                )

                hasMaxV -> SignalProbe(
                    "协商上限电压/电流（Android 14+）",
                    SignalStatus.DEGRADED,
                    "字段存在但厂商恒填 0"
                )

                else -> SignalProbe(
                    "协商上限电压/电流（Android 14+）",
                    SignalStatus.UNSUPPORTED,
                    "本机系统未提供该字段"
                )
            }
        )

        signals.add(probeUsbPorts())
        signals.add(probeThermal())
        signals.add(probeVendorExtras(extras))

        signals.add(
            sysfs(
                "充电器输出电压（usb/voltage_now）",
                "/sys/class/power_supply/usb/voltage_now"
            )
        )
        signals.add(
            sysfs(
                "充电类型（usb/charge_type）",
                "/sys/class/power_supply/usb/charge_type"
            )
        )
        signals.add(
            sysfs(
                "电池电流节点（battery/current_now）",
                "/sys/class/power_supply/battery/current_now"
            )
        )
        signals.add(
            sysfs(
                "无线接收功率（wls_rx/power）",
                "/sys/class/power_supply/wls_rx/power"
            )
        )
        signals.add(
            sysfs(
                "厂商闪充节点（vfcs-fchg/power）",
                "/sys/class/power_supply/vfcs-fchg/power"
            )
        )

        signals.add(
            SignalProbe(
                "充电器 VID/PID",
                SignalStatus.UNSUPPORTED,
                "手机作为 USB 设备充电时无法枚举适配器，需 Host 模式或外置分析仪"
            )
        )

        val available = signals.count { it.status == SignalStatus.AVAILABLE }
        val verdict = when {
            available >= 6 -> "本机可观测信号较多，协议推断可信度相对较高"
            available >= 4 -> "可观测信号有限，协议结论仅能作为概率性推断"
            else -> "可观测信号很少，本机不适合做精确协议识别"
        }

        return Capability(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            brand = DeviceProfile.current().brand.label,
            sdk = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE,
            signals = signals,
            availableCount = available,
            totalCount = signals.size,
            checkedAt = System.currentTimeMillis(),
            verdict = verdict
        )
    }

    /**
     * `UsbPort` 是 @SystemApi，公共 android.jar 里不存在，因此这里用反射探测。
     * 失败（SecurityException / NoSuchMethod）本身就是结论：普通应用拿不到端口诊断。
     */
    @SuppressLint("MissingPermission")
    private fun probeUsbPorts(): SignalProbe {
        val label = "USB-C 端口诊断（UsbPort）"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return SignalProbe(label, SignalStatus.UNSUPPORTED, "需要 Android 11+")
        }
        return try {
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: return SignalProbe(label, SignalStatus.UNSUPPORTED, "无 USB 服务")
            val ports = UsbManager::class.java.getMethod("getPorts").invoke(manager) as? List<*>
                ?: return SignalProbe(label, SignalStatus.BLOCKED, "getPorts() 未返回端口列表")
            if (ports.isEmpty()) {
                SignalProbe(
                    label,
                    SignalStatus.BLOCKED,
                    "端口列表为空：通常意味着缺少 MANAGE_USB 系统权限"
                )
            } else {
                val text = ports.filterNotNull().joinToString("；") { port ->
                    val cls = port.javaClass
                    val name = runCatching {
                        cls.getMethod("getPortName").invoke(port) as? String
                    }.getOrNull() ?: "port"
                    val status = runCatching {
                        cls.getMethod("getStatus").invoke(port) as? Int
                    }.getOrNull()
                    val warnings = runCatching {
                        val result = cls.getMethod("getComplianceWarnings").invoke(port)
                        (result as? IntArray)?.joinToString(",")
                    }.getOrNull()
                    buildString {
                        append(name)
                        append(" status=").append(status ?: "?")
                        if (!warnings.isNullOrEmpty()) append(" 告警码=").append(warnings)
                    }
                }
                SignalProbe(label, SignalStatus.AVAILABLE, text)
            }
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            SignalProbe(label, SignalStatus.BLOCKED, "${cause.javaClass.simpleName}：${cause.message ?: "被系统拒绝"}")
        }
    }

    private fun probeVendorExtras(extras: BatteryExtras): SignalProbe {
        val found = ArrayList<String>(6)
        if (extras.vendorSpeed >= 0) found.add("charging_speed=${extras.vendorSpeed}")
        if (extras.vendorAdapterPower >= 0) found.add("adapter_power=${extras.vendorAdapterPower}")
        if (extras.vendorInvalidCharger >= 0) found.add("invalid_charger=${extras.vendorInvalidCharger}")
        if (extras.vendorReverseWireless >= 0) found.add("reverse_wireless=${extras.vendorReverseWireless}")
        if (extras.vendorWlsOtg >= 0) found.add("wls_otg=${extras.vendorWlsOtg}")
        if (extras.cycleCount >= 0) found.add("cycle_count=${extras.cycleCount}")
        return if (found.isEmpty()) {
            SignalProbe("厂商扩展字段（私有）", SignalStatus.UNSUPPORTED, "本机电池广播未携带厂商自定义字段")
        } else {
            SignalProbe("厂商扩展字段（私有）", SignalStatus.AVAILABLE, found.joinToString("，"))
        }
    }

    /** Public since Android 10: tells us whether the SoC is throttling, which explains power drops. */
    private fun probeThermal(): SignalProbe {
        val label = "热状态（ThermalStatus）"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return SignalProbe(label, SignalStatus.UNSUPPORTED, "需要 Android 10+")
        }
        val manager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            ?: return SignalProbe(label, SignalStatus.UNSUPPORTED, "无 PowerManager")
        val status = runCatching { manager.currentThermalStatus }.getOrNull()
            ?: return SignalProbe(label, SignalStatus.BLOCKED, "读取失败")
        val text = when (status) {
            android.os.PowerManager.THERMAL_STATUS_NONE -> "NONE（无热限频）"
            android.os.PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT（轻度限频）"
            android.os.PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE（中度限频）"
            android.os.PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE（严重限频）"
            android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN($status)"
        }
        return SignalProbe(label, SignalStatus.AVAILABLE, text)
    }

    private fun sysfs(name: String, path: String): SignalProbe {
        val file = File(path)
        return try {
            val value = file.readText().trim().take(48)
            if (value.isEmpty()) {
                SignalProbe(name, SignalStatus.DEGRADED, "节点存在但为空")
            } else {
                SignalProbe(name, SignalStatus.AVAILABLE, "$value（$path）")
            }
        } catch (e: FileNotFoundException) {
            val message = e.message ?: ""
            if (message.contains("EACCES") || message.contains("Permission denied")) {
                SignalProbe(name, SignalStatus.BLOCKED, "SELinux 拒绝访问：$path")
            } else {
                SignalProbe(name, SignalStatus.UNSUPPORTED, "本机不存在：$path")
            }
        } catch (t: Throwable) {
            SignalProbe(name, SignalStatus.BLOCKED, "${t.javaClass.simpleName}：$path")
        }
    }
}
