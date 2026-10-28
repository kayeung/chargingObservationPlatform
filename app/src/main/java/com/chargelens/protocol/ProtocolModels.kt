package com.chargelens.protocol

import android.os.Build

enum class Brand(val label: String) {
    XIAOMI("小米 / 红米"),
    VIVO("vivo / iQOO"),
    MEIZU("魅族"),
    HUAWEI("华为"),
    HONOR("荣耀"),
    OPPO("OPPO / 一加 / realme"),
    SAMSUNG("三星"),
    GOOGLE("Google"),
    MOTOROLA("摩托罗拉"),
    ASUS("华硕"),
    SONY("索尼"),
    NOTHING("Nothing"),
    APPLE("苹果"),
    OTHER("其他品牌");

    companion object {
        fun from(raw: String): Brand {
            val s = raw.lowercase()
            return when {
                s.contains("xiaomi") || s.contains("redmi") || s.contains("poco") -> XIAOMI
                s.contains("vivo") || s.contains("iqoo") -> VIVO
                s.contains("meizu") -> MEIZU
                s.contains("huawei") -> HUAWEI
                s.contains("honor") -> HONOR
                s.contains("oppo") || s.contains("oneplus") || s.contains("realme") -> OPPO
                s.contains("samsung") -> SAMSUNG
                s.contains("google") -> GOOGLE
                s.contains("motorola") || s.contains("moto") -> MOTOROLA
                s.contains("asus") -> ASUS
                s.contains("sony") -> SONY
                s.contains("nothing") -> NOTHING
                s.contains("apple") -> APPLE
                else -> OTHER
            }
        }
    }
}

data class DeviceProfile(
    val brand: Brand,
    val manufacturer: String,
    val model: String,
    val device: String,
    val sdk: Int
) {
    val displayName: String
        get() = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ")

    companion object {
        fun current(): DeviceProfile = DeviceProfile(
            brand = Brand.from("${Build.MANUFACTURER} ${Build.BRAND}"),
            manufacturer = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            device = Build.DEVICE ?: "",
            sdk = Build.VERSION.SDK_INT
        )
    }
}

enum class ProtocolFamily(val label: String) {
    PD("USB PD"),
    PD_PPS("USB PD PPS"),
    QC("高通 Quick Charge"),
    BC12("BC 1.2 / USB"),
    AFC("三星 AFC"),
    SCP("华为 SCP"),
    FCP("华为 FCP"),
    VOOC("VOOC / SuperVOOC"),
    MI("小米 HyperCharge"),
    VIVO("vivo FlashCharge"),
    MEIZU("魅族 mCharge"),
    APPLE("Apple PD"),
    PE("联发科 PE"),
    QI("Qi 无线"),
    QI_PROP("私有无线快充"),
    UNKNOWN("未知")
}

enum class Transport(val label: String) {
    WIRED("有线"),
    WIRELESS("无线")
}

data class ProtocolProfile(
    val id: String,
    val name: String,
    val family: ProtocolFamily,
    val brand: Brand?,
    val wattRange: ClosedFloatingPointRange<Float>,
    val wireless: Boolean = false,
    val chargePump: Boolean? = null,
    val inputVoltageV: Float? = null,
    val needsBigStep: Boolean = false,
    val hint: String = ""
)

data class ProtocolCandidate(
    val profile: ProtocolProfile,
    val score: Float,
    val confidence: Int = 0,
    val evidence: List<String> = emptyList()
)

/**
 * An OEM-private field found in the battery broadcast (vivo exposes several). These are not part of
 * the public API and their value ranges are undocumented, so they are surfaced to the user instead
 * of being silently turned into a protocol verdict.
 */
data class VendorSignal(
    val key: String,
    val value: String,
    val note: String
)

data class ProtocolAssessment(
    val transport: Transport,
    val candidates: List<ProtocolCandidate>,
    val confidence: Int,
    val evidence: List<String>,
    val missingSignals: List<String>,
    val device: String,
    val updatedAt: Long,
    val disclaimer: String,
    val vendorSignals: List<VendorSignal> = emptyList()
) {
    val top: ProtocolCandidate? get() = candidates.firstOrNull()

    companion object {
        val MISSING_SIGNALS = listOf(
            "VBUS 实际电压与 USB PD PDO/PPS 契约（需读取充电 IC / UCSI）",
            "USB D+/D- 电平与 BC 1.2 检测结果（SDP/CDP/DCP）",
            "充电器厂商与型号（VID/PID，需 USB Host 模式）",
            "厂商充电驱动的 charge_type / 私有握手结果（sysfs 受 SELinux 保护）",
            "无线充电频率、线圈耦合状态与 Qi 版本",
            "适配器实时输出功率（墙端功率）"
        )

        const val DISCLAIMER =
            "结论为基于电池侧功率曲线、机型先验与系统公开字段的统计推断，不包含任何协议协商报文。"

        fun empty(device: String): ProtocolAssessment = ProtocolAssessment(
            transport = Transport.WIRED,
            candidates = emptyList(),
            confidence = 0,
            evidence = emptyList(),
            missingSignals = MISSING_SIGNALS,
            device = device,
            updatedAt = System.currentTimeMillis(),
            disclaimer = DISCLAIMER
        )
    }
}
