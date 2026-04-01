package io.slogr.agent.contracts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnumsTest {

    @Test fun `SlaGrade has three values`() {
        assertEquals(setOf("GREEN", "YELLOW", "RED"), SlaGrade.entries.map { it.name }.toSet())
    }

    @Test fun `Direction has two values`() {
        assertEquals(setOf("UPLINK", "DOWNLINK"), Direction.entries.map { it.name }.toSet())
    }

    @Test fun `TimingMode has two values`() {
        assertEquals(setOf("FIXED", "POISSON"), TimingMode.entries.map { it.name }.toSet())
    }

    @Test fun `TwampAuthMode has three values`() {
        assertEquals(
            setOf("UNAUTHENTICATED", "AUTHENTICATED", "ENCRYPTED"),
            TwampAuthMode.entries.map { it.name }.toSet()
        )
    }

    @Test fun `TracerouteMode has three values`() {
        assertEquals(setOf("ICMP", "UDP", "TCP"), TracerouteMode.entries.map { it.name }.toSet())
    }

    @Test fun `TargetDeviceType has four values`() {
        assertEquals(
            setOf("SLOGR_AGENT", "CISCO", "JUNIPER", "GENERIC_RFC5357"),
            TargetDeviceType.entries.map { it.name }.toSet()
        )
    }

    @Test fun `PublishStatus has three values`() {
        assertEquals(setOf("OK", "DEGRADED", "FAILING"), PublishStatus.entries.map { it.name }.toSet())
    }

    @Test fun `ConnectionMethod has two values`() {
        assertEquals(setOf("BOOTSTRAP_TOKEN", "API_KEY"), ConnectionMethod.entries.map { it.name }.toSet())
    }

    @Test fun `SlaGrade serializes to quoted string`() {
        assertEquals("\"GREEN\"", SlogrJson.encodeToString(SlaGrade.serializer(), SlaGrade.GREEN))
    }

    @Test fun `Direction serializes to quoted string`() {
        assertEquals("\"UPLINK\"", SlogrJson.encodeToString(Direction.serializer(), Direction.UPLINK))
    }

    @Test fun `PublishStatus round-trips`() {
        val values = PublishStatus.entries
        for (v in values) {
            val json = SlogrJson.encodeToString(PublishStatus.serializer(), v)
            val decoded = SlogrJson.decodeFromString(PublishStatus.serializer(), json)
            assertEquals(v, decoded)
        }
    }

    @Test fun `all enums are serializable`() {
        assertTrue(SlaGrade.entries.isNotEmpty())
        assertTrue(Direction.entries.isNotEmpty())
        assertTrue(TimingMode.entries.isNotEmpty())
        assertTrue(TwampAuthMode.entries.isNotEmpty())
        assertTrue(TracerouteMode.entries.isNotEmpty())
        assertTrue(TargetDeviceType.entries.isNotEmpty())
        assertTrue(PublishStatus.entries.isNotEmpty())
        assertTrue(ConnectionMethod.entries.isNotEmpty())
    }
}
