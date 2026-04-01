package io.slogr.agent.engine.twamp.controller

import io.slogr.agent.engine.twamp.FillMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SenderConfigTest {

    @Test fun `default senderConfig fields`() {
        val c = SenderConfig(count = 100, intervalMs = 20L, waitTimeMs = 5000L, paddingLength = 0, dscp = 0)
        assertEquals(100, c.count)
        assertEquals(20L, c.intervalMs)
        assertEquals(5000L, c.waitTimeMs)
        assertEquals(0, c.paddingLength)
        assertEquals(0, c.dscp)
        assertEquals(0, c.senderPort)
        assertEquals(2000L, c.recvTimeoutMs)
        assertEquals(FillMode.ZERO, c.fillMode)
        assertEquals(TimingMode.FIXED_INTERVAL, c.timingMode)
        assertEquals(0L, c.poissonMaxIntervalMs)
    }

    @Test fun `SenderResult with no packets has correct defaults`() {
        val r = SenderResult(packets = emptyList(), packetsSent = 0, packetsRecv = 0)
        assertEquals(0, r.packetsSent)
        assertEquals(0, r.packetsRecv)
        assertNull(r.error)
        assertTrue(r.packets.isEmpty())
    }

    @Test fun `PacketRecord stores fields correctly`() {
        val rec = PacketRecord(
            seq = 5,
            txNtp = 1L,
            rxNtp = 2L,
            fwdDelayMs = 1.5f,
            revDelayMs = 1.2f,
            reflectorProcNs = 100L,
            txTtl = 64,
            rxTtl = 63
        )
        assertEquals(5, rec.seq)
        assertEquals(1.5f, rec.fwdDelayMs)
        assertEquals(1.2f, rec.revDelayMs)
        assertFalse(rec.outOfOrder)
    }

    @Test fun `TimingMode values`() {
        assertNotNull(TimingMode.FIXED_INTERVAL)
        assertNotNull(TimingMode.POISSON)
    }
}
