package com.chargelens.protocol

import com.chargelens.protocol.Brand.APPLE
import com.chargelens.protocol.Brand.GOOGLE
import com.chargelens.protocol.Brand.HONOR
import com.chargelens.protocol.Brand.HUAWEI
import com.chargelens.protocol.Brand.MEIZU
import com.chargelens.protocol.Brand.MOTOROLA
import com.chargelens.protocol.Brand.OPPO
import com.chargelens.protocol.Brand.SAMSUNG
import com.chargelens.protocol.Brand.VIVO
import com.chargelens.protocol.Brand.XIAOMI

/**
 * Curated priors, not a protocol database.
 *
 * The watt buckets are the *typical* adapter classes shipped with those brand families. They are
 * intentionally ranges: the same "120W" phone can settle anywhere between 90 W and 125 W at the
 * battery depending on temperature, cable and state of charge.
 */
object ProtocolKnowledgeBase {

    val profiles: List<ProtocolProfile> = listOf(
        // ---------------------------------------------------------------- Xiaomi
        brand("mi_33", "小米 33W 快充（MI Turbo Charge）", ProtocolFamily.MI, XIAOMI, 26f..37f,
            pump = true, bigStep = true, hint = "33W 档，9V/3A 输入经电荷泵降压"),
        brand("mi_67", "小米 67W 快充（HyperCharge）", ProtocolFamily.MI, XIAOMI, 54f..71f,
            pump = true, bigStep = true, hint = "67W 档，双电芯 + 电荷泵"),
        brand("mi_90", "小米 90W 快充", ProtocolFamily.MI, XIAOMI, 74f..96f,
            pump = true, bigStep = true),
        brand("mi_120", "小米 120W 秒充（HyperCharge）", ProtocolFamily.MI, XIAOMI, 98f..126f,
            pump = true, bigStep = true, hint = "20V/6A 级输入，双电芯 6A 电荷泵"),
        brand("mi_200", "小米 200W 级秒充", ProtocolFamily.MI, XIAOMI, 165f..215f,
            pump = true, bigStep = true),
        wireless("mi_wl_30", "小米无线闪充 30W", ProtocolFamily.QI_PROP, XIAOMI, 19f..34f),
        wireless("mi_wl_50", "小米无线闪充 50W", ProtocolFamily.QI_PROP, XIAOMI, 38f..56f),
        wireless("mi_wl_80", "小米无线秒充 80W", ProtocolFamily.QI_PROP, XIAOMI, 58f..86f),

        // ------------------------------------------------------------------ vivo
        brand("vivo_44", "vivo 44W FlashCharge", ProtocolFamily.VIVO, VIVO, 32f..48f,
            pump = true, bigStep = true),
        brand("vivo_66", "vivo 66W FlashCharge", ProtocolFamily.VIVO, VIVO, 50f..71f,
            pump = true, bigStep = true),
        brand("vivo_80", "vivo 80W FlashCharge", ProtocolFamily.VIVO, VIVO, 70f..89f,
            pump = true, bigStep = true),
        brand("vivo_120", "vivo 120W FlashCharge", ProtocolFamily.VIVO, VIVO, 98f..126f,
            pump = true, bigStep = true, hint = "双电芯 6A 级电荷泵"),
        brand("vivo_200", "vivo 200W 级闪充", ProtocolFamily.VIVO, VIVO, 165f..215f,
            pump = true, bigStep = true),
        wireless("vivo_wl_50", "vivo 50W 无线闪充", ProtocolFamily.QI_PROP, VIVO, 38f..56f),

        // ----------------------------------------------------------------- Meizu
        brand("mz_24", "魅族 mCharge 24W", ProtocolFamily.MEIZU, MEIZU, 15f..27f,
            pump = false, bigStep = true),
        brand("mz_40", "魅族 mCharge 36–40W", ProtocolFamily.MEIZU, MEIZU, 30f..46f,
            pump = false, bigStep = true),
        brand("mz_65", "魅族 Super mCharge 55–65W", ProtocolFamily.MEIZU, MEIZU, 50f..71f,
            pump = true, bigStep = true),
        brand("mz_80", "魅族 Super mCharge 80W", ProtocolFamily.MEIZU, MEIZU, 70f..89f,
            pump = true, bigStep = true),
        wireless("mz_wl_20", "魅族无线充电 20W", ProtocolFamily.QI_PROP, MEIZU, 14f..24f),

        // ----------------------------------------------------------------- Huawei
        brand("hw_fcp_18", "华为 FCP 18W", ProtocolFamily.FCP, HUAWEI, 13f..21f,
            pump = false, inputVoltage = 9f, bigStep = true, hint = "9V/2A 固定档"),
        brand("hw_scp_22", "华为 SCP 22.5W", ProtocolFamily.SCP, HUAWEI, 19f..27f,
            pump = false, bigStep = true, hint = "SCP 低压大电流 4.5V/5A"),
        brand("hw_scp_40", "华为 SCP 40W", ProtocolFamily.SCP, HUAWEI, 33f..47f,
            pump = true, bigStep = true),
        brand("hw_scp_66", "华为 SCP 66W", ProtocolFamily.SCP, HUAWEI, 54f..72f,
            pump = true, bigStep = true),
        brand("hw_88", "华为 88W SuperCharge", ProtocolFamily.SCP, HUAWEI, 74f..93f,
            pump = true, bigStep = true),
        brand("hw_100", "华为 100W SuperCharge", ProtocolFamily.SCP, HUAWEI, 90f..106f,
            pump = true, bigStep = true),
        wireless("hw_wl_27", "华为无线快充 27W", ProtocolFamily.QI_PROP, HUAWEI, 20f..31f),
        wireless("hw_wl_50", "华为无线快充 50W", ProtocolFamily.QI_PROP, HUAWEI, 38f..56f),

        // ------------------------------------------------------------------ Honor
        brand("ho_66", "荣耀 66W SuperCharge", ProtocolFamily.SCP, HONOR, 54f..72f,
            pump = true, bigStep = true),
        brand("ho_100", "荣耀 100W SuperCharge", ProtocolFamily.SCP, HONOR, 88f..106f,
            pump = true, bigStep = true),
        wireless("ho_wl_50", "荣耀无线快充 50W", ProtocolFamily.QI_PROP, HONOR, 38f..56f),

        // ------------------------------------------------------- OPPO / OnePlus / realme
        brand("vooc_20", "VOOC 20W（5V/4A）", ProtocolFamily.VOOC, OPPO, 14f..25f,
            pump = false, inputVoltage = 5f, bigStep = true, hint = "低压直充，电池侧电流可达 4A"),
        brand("svooc_65", "SuperVOOC 65W", ProtocolFamily.VOOC, OPPO, 52f..72f,
            pump = true, bigStep = true),
        brand("svooc_80", "SuperVOOC 80W", ProtocolFamily.VOOC, OPPO, 68f..90f,
            pump = true, bigStep = true),
        brand("svooc_100", "SuperVOOC 100W", ProtocolFamily.VOOC, OPPO, 88f..108f,
            pump = true, bigStep = true),
        brand("svooc_150", "SuperVOOC 150W", ProtocolFamily.VOOC, OPPO, 125f..165f,
            pump = true, bigStep = true),
        brand("svooc_240", "SuperVOOC 240W", ProtocolFamily.VOOC, OPPO, 195f..255f,
            pump = true, bigStep = true),
        wireless("oppo_wl_50", "OPPO 无线闪充 50W", ProtocolFamily.QI_PROP, OPPO, 38f..56f),

        // ---------------------------------------------------------------- Samsung
        brand("ss_afc", "三星 AFC 15W", ProtocolFamily.AFC, SAMSUNG, 11f..19f,
            pump = false, inputVoltage = 9f, bigStep = true),
        brand("ss_sfc_25", "三星 SFC 25W", ProtocolFamily.PD_PPS, SAMSUNG, 21f..29f,
            pump = false, bigStep = true),
        brand("ss_sfc_45", "三星 SFC 45W", ProtocolFamily.PD_PPS, SAMSUNG, 34f..49f,
            pump = true, bigStep = true),
        wireless("ss_wl_15", "三星无线充电 15W", ProtocolFamily.QI, SAMSUNG, 9f..18f),

        // ----------------------------------------------------------------- Google
        brand("gg_18", "Pixel PD 18W", ProtocolFamily.PD, GOOGLE, 13f..22f,
            pump = false, inputVoltage = 9f, bigStep = true),
        brand("gg_30", "Pixel PD PPS 30W", ProtocolFamily.PD_PPS, GOOGLE, 24f..34f,
            pump = false, bigStep = true),
        wireless("gg_wl_15", "Pixel 无线充电 15W", ProtocolFamily.QI, GOOGLE, 9f..18f),

        // ------------------------------------------------------------------ Apple
        brand("ap_20", "Apple 20W PD", ProtocolFamily.APPLE, APPLE, 16f..24f,
            pump = false, inputVoltage = 9f, bigStep = true),
        brand("ap_30", "Apple 30W PD", ProtocolFamily.APPLE, APPLE, 25f..34f,
            pump = false, inputVoltage = 15f, bigStep = true),
        brand("ap_96", "Apple 96W PD", ProtocolFamily.APPLE, APPLE, 80f..100f,
            pump = false, inputVoltage = 20f, bigStep = true),

        // -------------------------------------------------------------- Motorola
        brand("moto_68", "摩托罗拉 68W TurboPower", ProtocolFamily.PD, MOTOROLA, 50f..76f),
        brand("moto_125", "摩托罗拉 125W TurboPower", ProtocolFamily.PD, MOTOROLA, 100f..130f),

        // ----------------------------------------------- public / generic profiles
        common("pd_pps", "USB PD PPS（公有协议）", ProtocolFamily.PD_PPS, 15f..240f,
            hint = "可编程电源，功率随电芯接受能力连续爬升"),
        common("pd_fixed", "USB PD 固定档（5/9/12/15/20V）", ProtocolFamily.PD, 10f..240f,
            hint = "固定 PDO，切换档位时出现功率台阶"),
        common("qc", "高通 Quick Charge 2/3/4+", ProtocolFamily.QC, 8f..36f,
            hint = "D+/D- 编码协商，QC3.0 以 200mV 步进"),
        common("bc12", "BC 1.2 / 普通 USB 充电", ProtocolFamily.BC12, 2f..13f,
            hint = "5V 输入，通常 1–2.4A"),
        wireless("qi_bpp", "Qi 基础功率 5W", ProtocolFamily.QI, null, 3f..7f),
        wireless("qi_epp", "Qi 扩展功率 7.5–15W", ProtocolFamily.QI, null, 8f..18f)
    )

    fun forTransport(wireless: Boolean): List<ProtocolProfile> = profiles.filter { it.wireless == wireless }

    private fun brand(
        id: String,
        name: String,
        family: ProtocolFamily,
        brand: Brand,
        range: ClosedFloatingPointRange<Float>,
        pump: Boolean? = null,
        inputVoltage: Float? = null,
        bigStep: Boolean = false,
        hint: String = ""
    ) = ProtocolProfile(
        id = id,
        name = name,
        family = family,
        brand = brand,
        wattRange = range,
        wireless = false,
        chargePump = pump,
        inputVoltageV = inputVoltage,
        needsBigStep = bigStep,
        hint = hint
    )

    private fun wireless(
        id: String,
        name: String,
        family: ProtocolFamily,
        brand: Brand?,
        range: ClosedFloatingPointRange<Float>
    ) = ProtocolProfile(
        id = id,
        name = name,
        family = family,
        brand = brand,
        wattRange = range,
        wireless = true
    )

    private fun common(
        id: String,
        name: String,
        family: ProtocolFamily,
        range: ClosedFloatingPointRange<Float>,
        hint: String = ""
    ) = ProtocolProfile(
        id = id,
        name = name,
        family = family,
        brand = null,
        wattRange = range,
        wireless = false,
        hint = hint
    )
}
