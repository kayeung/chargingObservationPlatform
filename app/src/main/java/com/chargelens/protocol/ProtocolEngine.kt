package com.chargelens.protocol

import com.chargelens.core.ChargeSample
import com.chargelens.core.Fmt
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Fuzzy inference of the charging protocol from battery-side telemetry.
 *
 * It never reads the actual negotiation: it compares the measured power/current/voltage trajectory
 * against the priors in [ProtocolKnowledgeBase] and the device brand, then reports ranked guesses
 * plus the evidence that produced them.
 */
class ProtocolEngine(private val kb: ProtocolKnowledgeBase = ProtocolKnowledgeBase) {

    fun assess(
        window: List<ChargeSample>,
        device: DeviceProfile,
        manualCharger: String = ""
    ): ProtocolAssessment {
        val features = Features.of(window) ?: return ProtocolAssessment.empty(device.displayName)
        val vendorSignals = buildVendorSignals(features)

        if (features.peakW < 1.5f) {
            return ProtocolAssessment(
                transport = features.transport,
                candidates = emptyList(),
                confidence = 0,
                evidence = listOf("当前功率过低（${Fmt.watts(features.peakW)} W），无法区分协议"),
                missingSignals = ProtocolAssessment.MISSING_SIGNALS,
                device = device.displayName,
                updatedAt = System.currentTimeMillis(),
                disclaimer = ProtocolAssessment.DISCLAIMER,
                vendorSignals = vendorSignals
            )
        }

        val scored = ArrayList<ProtocolCandidate>()
        kb.forTransport(features.transport == Transport.WIRELESS).forEach { profile ->
            score(profile, features, device, manualCharger)?.let { scored.add(it) }
        }

        val ranked = scored
            .filter { it.score > 0.8f }
            .sortedByDescending { it.score }
            .take(4)

        if (ranked.isEmpty()) return fallback(features, device)

        val total = ranked.fold(0f) { acc, c -> acc + c.score }.coerceAtLeast(0.001f)
        // Confidence also depends on how much evidence we have: 30s of data must not look as
        // certain as 10 minutes of data.
        val dataQuality = ((features.sampleCount / 180f).coerceIn(0.40f, 1f) *
            (features.durationMs / 120_000f).coerceIn(0.55f, 1f))
        val candidates = ranked.map { c ->
            c.copy(
                confidence = (c.score / total * 100f * dataQuality)
                    .roundToInt()
                    .coerceIn(2, 96)
            )
        }

        val summary = ArrayList<String>()
        summary.add(
            "峰值 ${Fmt.watts(features.peakW)} W · 均值 ${Fmt.watts(features.avgW)} W · " +
                "记录 ${Fmt.duration(features.durationMs)}"
        )
        summary.add(
            "电池侧电压 ${Fmt.volts(features.minV)}–${Fmt.volts(features.peakV)} V，" +
                "峰值电流 ${Fmt.amps(features.peakA)} A"
        )
        if (features.bigSteps > 0) {
            summary.add(
                "检测到 ${features.bigSteps} 次功率台阶，最大跳变 ${Fmt.watts(features.maxStepW)} W" +
                    "（握手/档位切换的典型形态）"
            )
        }
        if (features.chargePumpLike) {
            summary.add("电池侧电流 > 4 A 且功率 > 35 W：双电芯 + 电荷泵大电流的典型形态")
        }
        features.vinClass?.let {
            summary.add("系统上报协商上限电压 ${Fmt.volts(it)} V（Android 14+ 字段，部分厂商不填充）")
        }
        if (features.transport == Transport.WIRELESS) {
            summary.add("插电类型为 BATTERY_PLUGGED_WIRELESS，线圈侧参数不可读")
        }
        if (manualCharger.isNotBlank()) {
            summary.add("用户标注充电器：$manualCharger（参与品牌加权）")
        }
        summary.addAll(candidates.first().evidence.take(3))
        if (vendorSignals.isNotEmpty()) {
            summary.add(
                "厂商扩展字段：" + vendorSignals.take(4).joinToString("、") { "${it.key}=${it.value}" }
            )
        }

        return ProtocolAssessment(
            transport = features.transport,
            candidates = candidates,
            confidence = candidates.first().confidence,
            evidence = summary.distinct(),
            missingSignals = ProtocolAssessment.MISSING_SIGNALS,
            device = device.displayName,
            updatedAt = System.currentTimeMillis(),
            disclaimer = ProtocolAssessment.DISCLAIMER,
            vendorSignals = vendorSignals
        )
    }

    private fun score(
        profile: ProtocolProfile,
        f: Features,
        device: DeviceProfile,
        manualCharger: String
    ): ProtocolCandidate? {
        if (profile.wireless != (f.transport == Transport.WIRELESS)) return null
        // A private protocol of another brand cannot be negotiated by this phone.
        val profileBrand = profile.brand
        if (profileBrand != null && profileBrand != device.brand) return null

        var score = 0.7f
        val evidence = ArrayList<String>(5)

        if (profileBrand != null) {
            score += 3.4f
            evidence.add("机型品牌 ${profileBrand.label} 与该私有协议族一致")
            if (manualCharger.isNotBlank() && matchesBrand(profileBrand, manualCharger)) {
                score += 1.2f
                evidence.add("用户标注的充电器与品牌匹配")
            }
        }

        val lo = profile.wattRange.start
        val hi = profile.wattRange.endInclusive
        when {
            f.peakW >= lo && f.peakW <= hi -> {
                score += 3.2f
                evidence.add("峰值 ${Fmt.watts(f.peakW)} W 落在该档位 ${Fmt.watts(lo)}–${Fmt.watts(hi)} W")
            }

            f.peakW < lo -> {
                val gap = ((lo - f.peakW) / lo).coerceIn(0f, 1.5f)
                score -= 1.0f + 2.4f * gap
                if (gap > 0.35f) evidence.add("峰值明显低于该档位")
            }

            else -> {
                val gap = ((f.peakW - hi) / hi).coerceIn(0f, 1.5f)
                score -= 0.6f + 1.8f * gap
                if (gap > 0.25f) evidence.add("峰值高于该档位，可能是更高一档的适配器")
            }
        }

        profile.chargePump?.let { expected ->
            if (expected == f.chargePumpLike) {
                score += 1.3f
                evidence.add(
                    if (f.chargePumpLike) "电荷泵特征吻合（大电流 + 高功率）"
                    else "非电荷泵的降压直充形态吻合"
                )
            } else {
                score -= 1.1f
            }
        }

        profile.inputVoltageV?.let { expected ->
            val vin = f.vinClass
            if (vin != null) {
                if (abs(vin - expected) <= 1.2f) {
                    score += 1.4f
                    evidence.add("上报的协商电压 ${Fmt.volts(vin)} V 与该协议档位一致")
                } else {
                    score -= 1.2f
                }
            }
        }

        if (profile.needsBigStep && f.bigSteps == 0) {
            score -= 0.8f
        }

        // OEM flag: the charger failed the vendor's own validation, so a private handshake is unlikely.
        if (f.vendorInvalid == 1 && profile.brand != null) {
            score -= 2.5f
            if (profile.brand == device.brand) {
                evidence.add("系统标记 invalid_charger=1：私有协议可能未握手，当前大概率是公有协议回退")
            }
        }

        when (profile.family) {
            ProtocolFamily.PD_PPS -> {
                if (f.bigSteps <= 1) {
                    score += 1.1f
                    evidence.add("功率曲线平滑爬升，缺少固定档位跳变：PPS 特征")
                }
                val vinClass = f.vinClass
                if (vinClass != null && vinClass in 9f..21f) score += 0.6f
            }

            ProtocolFamily.PD -> {
                if (f.bigSteps > 0) {
                    score += 0.9f
                    evidence.add("出现 ${f.bigSteps} 次功率台阶：固定 PDO 切换特征")
                }
            }

            ProtocolFamily.QC, ProtocolFamily.AFC -> {
                if (f.bigSteps > 0 && f.peakW < 40f) score += 0.5f
            }

            ProtocolFamily.BC12 -> {
                if (f.peakW <= 13f) {
                    score += 1.2f
                    evidence.add("峰值低于 13 W，符合 5V 基础充电能力")
                }
            }

            ProtocolFamily.QI -> {
                if (f.peakW <= 18f) score += 1.0f
            }

            ProtocolFamily.QI_PROP -> {
                if (f.peakW > 16f) {
                    score += 1.0f
                    evidence.add("无线侧功率超过 15 W，超出 Qi EPP 上限")
                }
            }

            else -> Unit
        }

        return ProtocolCandidate(profile = profile, score = score, evidence = evidence)
    }

    private fun fallback(f: Features, device: DeviceProfile): ProtocolAssessment {
        val unknown = ProtocolProfile(
            id = "unknown",
            name = "未识别（信号不足）",
            family = ProtocolFamily.UNKNOWN,
            brand = null,
            wattRange = 0f..1000f
        )
        return ProtocolAssessment(
            transport = f.transport,
            candidates = listOf(ProtocolCandidate(unknown, 1f, 12, listOf("功率曲线特征不足以匹配任何已知档位"))),
            confidence = 12,
            evidence = listOf(
                "峰值 ${Fmt.watts(f.peakW)} W，但缺乏台阶/电压平台等可区分特征",
                "建议在低电量（<40%）阶段重新记录以获得更完整的握手过程"
            ),
            missingSignals = ProtocolAssessment.MISSING_SIGNALS,
            device = device.displayName,
            updatedAt = System.currentTimeMillis(),
            disclaimer = ProtocolAssessment.DISCLAIMER,
            vendorSignals = buildVendorSignals(f)
        )
    }

    private fun buildVendorSignals(f: Features): List<VendorSignal> {
        val sample = f.vendorSource ?: return emptyList()
        val out = ArrayList<VendorSignal>(6)
        if (sample.vendorSpeed >= 0) {
            out.add(VendorSignal("charging_speed", sample.vendorSpeed.toString(), "厂商私有充电档位/状态枚举，语义需按机型标定"))
        }
        if (sample.vendorAdapterPower >= 0) {
            out.add(VendorSignal("adapter_power", sample.vendorAdapterPower.toString(), "适配器上报功率原始值（单位未公开）"))
        }
        if (sample.vendorInvalid >= 0) {
            out.add(
                VendorSignal(
                    "invalid_charger",
                    sample.vendorInvalid.toString(),
                    if (sample.vendorInvalid == 1) "系统认为充电器未通过校验" else "充电器校验通过"
                )
            )
        }
        if (sample.vendorReverseWireless >= 0) {
            out.add(VendorSignal("reverse_wireless_chg_state", sample.vendorReverseWireless.toString(), "反向无线充电状态"))
        }
        if (sample.vendorWlsOtg >= 0) {
            out.add(VendorSignal("wls_otg_status", sample.vendorWlsOtg.toString(), "无线 OTG 状态"))
        }
        if (sample.cycleCount >= 0) {
            out.add(VendorSignal("cycle_count", sample.cycleCount.toString(), "电池循环次数"))
        }
        return out
    }

    private fun matchesBrand(brand: Brand, text: String): Boolean {
        val t = text.lowercase()
        return when (brand) {
            Brand.XIAOMI -> listOf("小米", "xiaomi", "红米", "redmi", "poco").any { t.contains(it) }
            Brand.VIVO -> listOf("vivo", "iqoo", "爱酷").any { t.contains(it) }
            Brand.MEIZU -> listOf("魅族", "meizu").any { t.contains(it) }
            Brand.HUAWEI -> listOf("华为", "huawei").any { t.contains(it) }
            Brand.HONOR -> listOf("荣耀", "honor").any { t.contains(it) }
            Brand.OPPO -> listOf("oppo", "一加", "oneplus", "realme", "真我").any { t.contains(it) }
            Brand.SAMSUNG -> listOf("三星", "samsung").any { t.contains(it) }
            Brand.APPLE -> listOf("apple", "苹果").any { t.contains(it) }
            else -> false
        }
    }

    /** Aggregated trajectory features of the observation window. */
    internal data class Features(
        val peakW: Float,
        val avgW: Float,
        val peakA: Float,
        val peakV: Float,
        val minV: Float,
        val socStart: Int,
        val socEnd: Int,
        val durationMs: Long,
        val bigSteps: Int,
        val maxStepW: Float,
        val chargePumpLike: Boolean,
        val vinClass: Float?,
        val sampleCount: Int,
        val vendorSpeed: Int,
        val vendorInvalid: Int,
        val vendorAdapterPower: Int,
        val vendorSource: ChargeSample?,
        val transport: Transport
    ) {
        companion object {
            fun of(window: List<ChargeSample>): Features? {
                // 2.0–12.0 V covers single-cell, 2S dual-cell packs and a few 3S designs.
                val valid = window.filter { !it.isGap && it.voltageMv in 2_000..12_000 }
                if (valid.size < 3) return null

                val peakW = valid.maxOf { it.powerW }
                val peakA = valid.maxOf { it.currentA }
                val peakV = valid.maxOf { it.voltageV }
                val minV = valid.minOf { it.voltageV }
                val positive = valid.filter { it.powerW > 0.2f }
                val avgW = if (positive.isEmpty()) 0f else positive.sumOf { it.powerW.toDouble() }.toFloat() / positive.size

                var steps = 0
                var maxStep = 0f
                for (i in 1 until valid.size) {
                    val dt = valid[i].ts - valid[i - 1].ts
                    if (dt <= 0L || dt > 4_000L) continue
                    val delta = abs(valid[i].powerW - valid[i - 1].powerW)
                    if (delta >= 5f) {
                        steps++
                        if (delta > maxStep) maxStep = delta
                    }
                }

                val vin = valid.lastOrNull { it.maxChargingVoltageUv >= 3_000_000 }
                    ?.let { it.maxChargingVoltageUv / 1_000_000f }

                return Features(
                    peakW = peakW,
                    avgW = avgW,
                    peakA = peakA,
                    peakV = peakV,
                    minV = minV,
                    socStart = valid.first().soc,
                    socEnd = valid.last().soc,
                    durationMs = (valid.last().ts - valid.first().ts).coerceAtLeast(0L),
                    bigSteps = steps,
                    maxStepW = maxStep,
                    chargePumpLike = peakA >= 4.0f && peakW >= 35f,
                    vinClass = vin,
                    sampleCount = valid.size,
                    vendorSpeed = valid.last().vendorSpeed,
                    vendorInvalid = valid.last().vendorInvalid,
                    vendorAdapterPower = valid.last().vendorAdapterPower,
                    vendorSource = valid.last(),
                    transport = if (valid.last().isWireless) Transport.WIRELESS else Transport.WIRED
                )
            }
        }
    }
}
