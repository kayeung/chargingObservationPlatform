package com.chargelens.protocol

import com.chargelens.core.ChargeSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the inference engine. They do not validate physics against a real charger
 * (impossible without hardware), they validate that the scoring ranks the expected profile for a
 * synthetic trajectory and that brand isolation works.
 */
class ProtocolEngineTest {

    private val engine = ProtocolEngine()

    private val vivo = DeviceProfile(Brand.VIVO, "vivo", "V2454A", "PD2454", 36)
    private val xiaomi = DeviceProfile(Brand.XIAOMI, "Xiaomi", "23127PN0CC", "fuxi", 34)

    @Test
    fun `power is battery voltage times battery current`() {
        val sample = sample(ts = 1_000L, voltageMv = 4_000, currentUa = 2_000_000)
        assertEquals(8_000L, sample.powerMw)
        assertEquals(8f, sample.powerW, 0.001f)
    }

    @Test
    fun `vivo two cell charge pump trace is ranked first on vivo hardware`() {
        val assessment = engine.assess(series(voltageMv = 8_600, currentUa = 12_000_000), vivo)

        assertEquals("vivo_120", assessment.top?.profile?.id)
        assertEquals(Brand.VIVO, assessment.top?.profile?.brand)
        assertTrue("confidence should be reported", assessment.confidence > 20)
        assertTrue(assessment.evidence.any { it.contains("电荷泵") })
        assertTrue(assessment.candidates.all { it.profile.brand != Brand.XIAOMI })
    }

    @Test
    fun `xiaomi 67W trace matches the xiaomi bucket and excludes vivo`() {
        val assessment = engine.assess(series(voltageMv = 4_200, currentUa = 16_000_000), xiaomi)

        assertEquals("mi_67", assessment.top?.profile?.id)
        assertTrue(assessment.candidates.none { it.profile.brand == Brand.VIVO })
    }

    @Test
    fun `wireless trace only returns wireless candidates`() {
        val assessment = engine.assess(
            series(voltageMv = 4_200, currentUa = 2_400_000, plugged = 4),
            vivo
        )

        assertEquals(Transport.WIRELESS, assessment.transport)
        assertEquals("qi_epp", assessment.top?.profile?.id)
        assertTrue(assessment.candidates.all { it.profile.wireless })
    }

    @Test
    fun `weak trace falls back instead of inventing certainty`() {
        val assessment = engine.assess(series(voltageMv = 4_200, currentUa = 30_000), vivo)

        assertTrue(assessment.top == null || assessment.confidence <= 20)
    }

    @Test
    fun `knowledge base ids are unique and ranges are sane`() {
        val ids = ProtocolKnowledgeBase.profiles.map { it.id }
        assertEquals("duplicate profile ids", ids.size, ids.toSet().size)
        ProtocolKnowledgeBase.profiles.forEach { profile ->
            assertTrue("bad range: ${profile.id}", profile.wattRange.start < profile.wattRange.endInclusive)
            assertTrue("blank name: ${profile.id}", profile.name.isNotBlank())
        }
    }

    private fun series(
        voltageMv: Int,
        currentUa: Int,
        plugged: Int = 1,
        count: Int = 120
    ): List<ChargeSample> {
        val start = 1_700_000_000_000L
        return (0 until count).map { index ->
            sample(
                ts = start + index * 1_000L,
                voltageMv = voltageMv,
                currentUa = if (index < 6) currentUa / 8 else currentUa,
                plugged = plugged,
                soc = 20 + index / 20,
                counter = 5_000_000 + index * 2_000
            )
        }
    }

    private fun sample(
        ts: Long,
        voltageMv: Int,
        currentUa: Int,
        plugged: Int = 1,
        soc: Int = 30,
        counter: Int = 5_000_000
    ) = ChargeSample(
        ts = ts,
        voltageMv = voltageMv,
        currentUa = currentUa,
        soc = soc,
        tempC = 33f,
        plugged = plugged,
        status = 2,
        chargeCounterUah = counter,
        maxChargingVoltageUv = 0,
        maxChargingCurrentUa = 0,
        chargingStatus = 0
    )
}
