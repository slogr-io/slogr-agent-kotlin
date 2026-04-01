package io.slogr.agent.engine.sla

import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.contracts.TimingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProfileRegistryTest {

    @Test
    fun `all 24 profiles are loaded from bundled profiles json`() {
        assertEquals(24, ProfileRegistry.all().size)
    }

    @Test
    fun `voip profile has correct values`() {
        val p = ProfileRegistry.get("voip")
        assertNotNull(p)
        assertEquals(50, p!!.nPackets)
        assertEquals(20L, p.intervalMs)
        assertEquals(46, p.dscp)
        assertEquals(200, p.packetSize)
        assertEquals(150f, p.rttGreenMs)
        assertEquals(300f, p.rttRedMs)
        assertEquals(15f, p.jitterGreenMs)
        assertEquals(30f, p.jitterRedMs)
        assertEquals(0.5f, p.lossGreenPct)
        assertEquals(1.0f, p.lossRedPct)
        assertEquals(TimingMode.FIXED, p.timingMode)
    }

    @Test
    fun `gaming profile has correct values`() {
        val p = ProfileRegistry.get("gaming")
        assertNotNull(p)
        assertEquals(20, p!!.nPackets)
        assertEquals(20f, p.rttGreenMs)
        assertEquals(50f, p.rttRedMs)
    }

    @Test
    fun `unknown profile name returns null`() {
        assertNull(ProfileRegistry.get("nonexistent-profile"))
    }

    @Test
    fun `update replaces profile set atomically`() {
        val originals = ProfileRegistry.all()

        val custom = SlaProfile(
            name = "custom-test",
            nPackets = 5,
            intervalMs = 100,
            waitTimeMs = 1000,
            dscp = 0,
            packetSize = 64,
            timingMode = TimingMode.FIXED,
            rttGreenMs = 50f,
            rttRedMs = 100f,
            jitterGreenMs = 10f,
            jitterRedMs = 25f,
            lossGreenPct = 1f,
            lossRedPct = 5f
        )
        try {
            ProfileRegistry.update(listOf(custom))
            assertEquals(1, ProfileRegistry.all().size)
            assertNotNull(ProfileRegistry.get("custom-test"))
        } finally {
            // Restore defaults so other tests are unaffected
            ProfileRegistry.update(originals)
        }
    }

    @Test
    fun `all profiles have positive packet counts and intervals`() {
        for (p in ProfileRegistry.all()) {
            assert(p.nPackets > 0) { "${p.name}: nPackets must be > 0" }
            assert(p.intervalMs > 0) { "${p.name}: intervalMs must be > 0" }
            assert(p.rttGreenMs < p.rttRedMs) { "${p.name}: green RTT must be < red RTT" }
            assert(p.jitterGreenMs < p.jitterRedMs) { "${p.name}: green jitter must be < red jitter" }
            assert(p.lossGreenPct < p.lossRedPct) { "${p.name}: green loss must be < red loss" }
        }
    }
}
